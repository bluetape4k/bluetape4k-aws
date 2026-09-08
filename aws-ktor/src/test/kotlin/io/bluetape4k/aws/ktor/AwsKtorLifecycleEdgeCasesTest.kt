package io.bluetape4k.aws.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchKtorPlugin
import io.bluetape4k.aws.ktor.eventbridge.EventBridgeKtorPlugin
import io.bluetape4k.aws.ktor.imds.ImdsKtorPlugin
import io.bluetape4k.aws.ktor.kinesis.KinesisKtorPlugin
import io.bluetape4k.aws.ktor.s3.accessgrants.S3AccessGrantsKtorPlugin
import io.bluetape4k.aws.ktor.s3vectors.S3VectorsKtorPlugin
import io.bluetape4k.aws.ktor.ses.SesKtorPlugin
import io.bluetape4k.aws.ktor.sns.SnsKtorPlugin
import io.bluetape4k.aws.ktor.sts.StsKtorPlugin
import io.bluetape4k.aws.ktor.sts.StsKtorRuntime
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.core.ApplicationResourceClosePhase
import io.bluetape4k.ktor.core.ApplicationResourceRegistry
import io.bluetape4k.ktor.core.ApplicationResourceRegistryState
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.imds.Ec2MetadataRetryPolicy
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClientBuilder
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClientBuilder
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder
import software.amazon.awssdk.services.s3control.S3ControlAsyncClient
import software.amazon.awssdk.services.s3control.S3ControlAsyncClientBuilder
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClientBuilder
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient
import software.amazon.awssdk.services.sesv2.SesV2AsyncClientBuilder
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.SnsAsyncClientBuilder
import software.amazon.awssdk.services.sts.StsAsyncClient
import software.amazon.awssdk.services.sts.StsAsyncClientBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AwsKtorLifecycleEdgeCasesTest {

    @Test
    fun `all simple plugins keep owned clients open until ApplicationStopped`() {
        val clients = SimpleClients()
        val closeCounts = clients.closeCounts

        mockkStatic(
            StsAsyncClient::class,
            SnsAsyncClient::class,
            SesV2AsyncClient::class,
            EventBridgeAsyncClient::class,
            KinesisAsyncClient::class,
            S3VectorsAsyncClient::class,
            CloudWatchAsyncClient::class,
            Ec2MetadataAsyncClient::class,
            S3ControlAsyncClient::class,
        )
        try {
            clients.configureBuilders()

            val stoppingCounts = mutableListOf<Int>()
            testApplication {
                application {
                    install(StsKtorPlugin)
                    install(SnsKtorPlugin)
                    install(SesKtorPlugin)
                    install(EventBridgeKtorPlugin)
                    install(KinesisKtorPlugin)
                    install(S3VectorsKtorPlugin)
                    install(CloudWatchKtorPlugin)
                    install(ImdsKtorPlugin)
                    install(S3AccessGrantsKtorPlugin)
                    monitor.subscribe(ApplicationStopping) {
                        stoppingCounts += closeCounts.map { it.get() }.sum()
                    }
                }

                startApplication()
            }

            stoppingCounts shouldBeEqualTo listOf(0)
            closeCounts.forEach { it.get() shouldBeEqualTo 1 }
        } finally {
            unmockkStatic(StsAsyncClient::class)
            unmockkStatic(SnsAsyncClient::class)
            unmockkStatic(SesV2AsyncClient::class)
            unmockkStatic(EventBridgeAsyncClient::class)
            unmockkStatic(KinesisAsyncClient::class)
            unmockkStatic(S3VectorsAsyncClient::class)
            unmockkStatic(CloudWatchAsyncClient::class)
            unmockkStatic(Ec2MetadataAsyncClient::class)
            unmockkStatic(S3ControlAsyncClient::class)
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
        registry.closeReport.failures shouldBeEqualTo emptyList()
        registry.closeReport.closed shouldBeEqualTo 1
    }

    @Test
    fun `direct runtime stop and registry close close a client once`() = runSuspendIO {
        val client = mockk<StsAsyncClient>(relaxed = true)
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
            check(releaseDelayed.await(5, TimeUnit.SECONDS))
            order += "delayed"
        }

        try {
            val closeTask = executor.submit { registry.close() }
            delayedStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            nextClosed.get().shouldBeFalse()
            order shouldBeEqualTo emptyList()

            releaseDelayed.countDown()
            closeTask.get(5, TimeUnit.SECONDS)
            order shouldBeEqualTo listOf("delayed", "next")
        } finally {
            releaseDelayed.countDown()
            executor.shutdownNow()
        }
    }

    private class SimpleClients {
        val stsClient = mockk<StsAsyncClient>(relaxed = true)
        val snsClient = mockk<SnsAsyncClient>(relaxed = true)
        val sesClient = mockk<SesV2AsyncClient>(relaxed = true)
        val eventBridgeClient = mockk<EventBridgeAsyncClient>(relaxed = true)
        val kinesisClient = mockk<KinesisAsyncClient>(relaxed = true)
        val s3VectorsClient = mockk<S3VectorsAsyncClient>(relaxed = true)
        val cloudWatchClient = mockk<CloudWatchAsyncClient>(relaxed = true)
        val imdsClient = mockk<Ec2MetadataAsyncClient>(relaxed = true)
        val s3ControlClient = mockk<S3ControlAsyncClient>(relaxed = true)
        val closeCounts = List(9) { AtomicInteger() }
        init {
            every { stsClient.close() } answers { closeCounts[0].incrementAndGet() }
            every { snsClient.close() } answers { closeCounts[1].incrementAndGet() }
            every { sesClient.close() } answers { closeCounts[2].incrementAndGet() }
            every { eventBridgeClient.close() } answers { closeCounts[3].incrementAndGet() }
            every { kinesisClient.close() } answers { closeCounts[4].incrementAndGet() }
            every { s3VectorsClient.close() } answers { closeCounts[5].incrementAndGet() }
            every { cloudWatchClient.close() } answers { closeCounts[6].incrementAndGet() }
            every { imdsClient.close() } answers { closeCounts[7].incrementAndGet() }
            every { s3ControlClient.close() } answers { closeCounts[8].incrementAndGet() }
        }

        val stsBuilder = mockk<StsAsyncClientBuilder>(relaxed = true)
        val snsBuilder = mockk<SnsAsyncClientBuilder>(relaxed = true)
        val sesBuilder = mockk<SesV2AsyncClientBuilder>(relaxed = true)
        val eventBridgeBuilder = mockk<EventBridgeAsyncClientBuilder>(relaxed = true)
        val kinesisBuilder = mockk<KinesisAsyncClientBuilder>(relaxed = true)
        val s3VectorsBuilder = mockk<S3VectorsAsyncClientBuilder>(relaxed = true)
        val cloudWatchBuilder = mockk<CloudWatchAsyncClientBuilder>(relaxed = true)
        val imdsBuilder = mockk<Ec2MetadataAsyncClient.Builder>(relaxed = true)
        val s3ControlBuilder = mockk<S3ControlAsyncClientBuilder>(relaxed = true)


        fun configureBuilders() {
            every { StsAsyncClient.builder() } returns stsBuilder
            every { stsBuilder.build() } returns stsClient
            every { SnsAsyncClient.builder() } returns snsBuilder
            every { snsBuilder.build() } returns snsClient
            every { SesV2AsyncClient.builder() } returns sesBuilder
            every { sesBuilder.build() } returns sesClient
            every { EventBridgeAsyncClient.builder() } returns eventBridgeBuilder
            every { eventBridgeBuilder.build() } returns eventBridgeClient
            every { KinesisAsyncClient.builder() } returns kinesisBuilder
            every { kinesisBuilder.build() } returns kinesisClient
            every { S3VectorsAsyncClient.builder() } returns s3VectorsBuilder
            every { s3VectorsBuilder.build() } returns s3VectorsClient
            every { CloudWatchAsyncClient.builder() } returns cloudWatchBuilder
            every { cloudWatchBuilder.build() } returns cloudWatchClient
            every { Ec2MetadataAsyncClient.builder() } returns imdsBuilder
            every { imdsBuilder.tokenTtl(any()) } returns imdsBuilder
            every { imdsBuilder.endpointMode(any()) } returns imdsBuilder
            every { imdsBuilder.endpoint(any()) } returns imdsBuilder
            every { imdsBuilder.retryPolicy(any<Ec2MetadataRetryPolicy>()) } returns imdsBuilder
            every { imdsBuilder.httpClient(any<SdkAsyncHttpClient>()) } returns imdsBuilder
            every { imdsBuilder.build() } returns imdsClient
            every { S3ControlAsyncClient.builder() } returns s3ControlBuilder
            every { s3ControlBuilder.build() } returns s3ControlClient

        }
    }
}
