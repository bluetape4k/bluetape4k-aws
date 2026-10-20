package io.bluetape4k.aws.ktor

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.ktor.imds.ImdsKtorPlugin
import io.bluetape4k.aws.ktor.imds.ImdsHttpClientFactory
import io.bluetape4k.aws.ktor.kinesis.KinesisKtorPlugin
import io.bluetape4k.aws.ktor.sts.StsKtorRuntime
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.core.ApplicationResourceClosePhase
import io.bluetape4k.ktor.core.ApplicationResourceRegistry
import io.bluetape4k.ktor.core.ApplicationResourceRegistryState
import io.bluetape4k.logging.KLogging
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import aws.sdk.kotlin.services.sts.StsClient as KotlinStsClient
import aws.smithy.kotlin.runtime.http.engine.CloseableHttpClientEngine
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class AwsKtorLifecycleEdgeCasesTest {

    companion object: KLogging()

    @Test
    fun `Kinesis and IMDS clients remain open until ApplicationStopped`() {
        val clients = SimpleClients()
        val closeCounts = clients.closeCounts
        val imdsEngine = mockk<CloseableHttpClientEngine>(relaxed = true)

        mockkStatic(KinesisAsyncClient::class)
        mockkObject(ImdsHttpClientFactory)
        try {
            clients.configureBuilders()
            every { ImdsHttpClientFactory.createEngine(any()) } returns imdsEngine

            val stoppingCounts = mutableListOf<Int>()
            testApplication {
                application {
                    install(KinesisKtorPlugin)
                    install(ImdsKtorPlugin)
                    monitor.subscribe(ApplicationStopping) {
                        stoppingCounts += closeCounts.map { it.get() }.sum()
                    }
                }

                startApplication()
            }

            stoppingCounts shouldBeEqualTo listOf(0)
            closeCounts.forEach { it.get() shouldBeEqualTo 1 }
            verify(exactly = 1) { imdsEngine.close() }
        } finally {
            unmockkStatic(KinesisAsyncClient::class)
            unmockkObject(ImdsHttpClientFactory)
        }
    }

    @Test
    fun `registry closes in reverse order and continues after an ordinary failure`() {
        val order = mutableListOf<String>()
        val registry = ApplicationResourceRegistry()

        registry.register { order += "first" }
        registry.register {
            order += "middle"
            error("synthetic close failure")
        }
        registry.register { order += "last" }

        registry.close()

        order shouldBeEqualTo listOf("last", "middle", "first")
        registry.closeReport.state shouldBeEqualTo ApplicationResourceRegistryState.CLOSED
        registry.closeReport.closed shouldBeEqualTo 2
        registry.closeReport.failures.single().apply {
            phase shouldBeEqualTo ApplicationResourceClosePhase.SHUTDOWN
            fatal.shouldBeFalse()
        }
    }

    @Test
    fun `registration after registry close is closed immediately`() {
        val closeCount = AtomicInteger()
        val registry = ApplicationResourceRegistry()

        registry.close()
        registry.register { closeCount.incrementAndGet() }

        closeCount.get() shouldBeEqualTo 1
        registry.closeReport.failures.shouldBeEmpty()
        registry.closeReport.closed shouldBeEqualTo 1
    }

    @Test
    fun `direct runtime stop and registry close close a client once`() = runSuspendIO {
        val client = mockk<KotlinStsClient>(relaxed = true)
        val runtime = StsKtorRuntime(mockk(relaxed = true), client)
        val registry = ApplicationResourceRegistry()

        runtime.registerApplicationResources(registry)
        runtime.stop()

        registry.close()
        runtime.stop()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `registry waits for a delayed close before closing the next resource`() {
        val delayedStarted = CountDownLatch(1)
        val releaseDelayed = CountDownLatch(1)
        val nextClosed = AtomicBoolean(false)
        val order = mutableListOf<String>()
        val registry = ApplicationResourceRegistry()
        val executor = Executors.newSingleThreadExecutor()

        registry.register {
            order += "next"
            nextClosed.set(true)
        }
        registry.register {
            delayedStarted.countDown()
            check(releaseDelayed.await(5.seconds))
            order += "delayed"
        }

        try {
            val closeTask = executor.submit { registry.close() }
            delayedStarted.await(5.seconds).shouldBeTrue()
            nextClosed.get().shouldBeFalse()
            order.shouldBeEmpty()

            releaseDelayed.countDown()
            closeTask.get(5.seconds)
            order shouldBeEqualTo listOf("delayed", "next")
        } finally {
            releaseDelayed.countDown()
            executor.shutdownNow()
        }
    }

    private class SimpleClients {
        val kinesisClient = mockk<KinesisAsyncClient>(relaxed = true)
        val closeCounts = List(1) { AtomicInteger() }

        init {
            every { kinesisClient.close() } answers { closeCounts[0].incrementAndGet() }
        }

        val kinesisBuilder = mockk<KinesisAsyncClientBuilder>(relaxed = true)

        fun configureBuilders() {
            every { KinesisAsyncClient.builder() } returns kinesisBuilder
            every { kinesisBuilder.build() } returns kinesisClient
        }
    }
}
