package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.invoking
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldStartWith
import io.bluetape4k.codec.Base58
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.batchmanager.SqsAsyncBatchManager
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SqsBatchTransportFactoryTest {

    @Test
    fun `factory connects properties client scheduler manager and adapter`() {
        val client = mockk<SqsAsyncClient>()
        val manager = mockk<SqsAsyncBatchManager>()
        every { manager.close() } returns Unit
        val transport = mockk<SqsBatchTransport>()
        val scheduler = TrackingScheduledExecutor()
        val capturedProperties = AtomicReference<SqsBatchProperties>()
        val capturedClient = AtomicReference<SqsAsyncClient>()
        val capturedScheduler = AtomicReference<ScheduledThreadPoolExecutor>()
        val properties = batchProperties()

        val resources = SqsBatchTransportFactory.create(
            properties = properties,
            client = client,
            schedulerFactory = { scheduler },
            managerFactory = { actualProperties, actualClient, actualScheduler ->
                capturedProperties.set(actualProperties)
                capturedClient.set(actualClient)
                capturedScheduler.set(actualScheduler)
                manager
            },
            transportFactory = { transport },
        )

        resources.transport shouldBeSameInstanceAs transport
        capturedProperties.get() shouldBeSameInstanceAs properties
        capturedClient.get() shouldBeSameInstanceAs client
        capturedScheduler.get() shouldBeSameInstanceAs scheduler
        resources.closeManager()
        resources.shutdownExecutorNow()
        verify(exactly = 1) { manager.close() }
        verify(exactly = 0) { client.close() }
        scheduler.shutdownNowCount shouldBeEqualTo 1
    }

    @Test
    fun `manager build failure shuts down scheduler without closing caller client`() {
        val secret = "manager-${Base58.randomString(16)}"
        val client = mockk<SqsAsyncClient>()
        val scheduler = TrackingScheduledExecutor()

        val error = invoking {
            SqsBatchTransportFactory.create(
                properties = batchProperties(),
                client = client,
                schedulerFactory = { scheduler },
                managerFactory = { _, _, _ -> throw IllegalStateException(secret) },
                transportFactory = { error("must not create transport") },
            )
        } shouldThrow SqsBatchStartupException::class

        error.startupComponent shouldBeEqualTo SqsBatchStartupComponent.MANAGER
        error.cleanupComponents.shouldBeEmpty()
        error.cause.shouldBeNull()
        error.suppressed.shouldBeEmpty()
        error.toString() shouldNotContain secret
        scheduler.shutdownNowCount shouldBeEqualTo 1
        scheduler.isShutdown.shouldBeTrue()
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `manager build cleanup restores the interrupted status`() {
        Thread.interrupted()
        try {
            val client = mockk<SqsAsyncClient>()
            val scheduler = TrackingScheduledExecutor(interruptOnAwait = true)

            val error = invoking {
                SqsBatchTransportFactory.create(
                    properties = batchProperties(),
                    client = client,
                    schedulerFactory = { scheduler },
                    managerFactory = { _, _, _ ->
                        throw IllegalStateException("manager-${Base58.randomString(16)}")
                    },
                    transportFactory = { error("must not create transport") },
                )
            } shouldThrow SqsBatchStartupException::class

            error.cleanupComponents shouldBeEqualTo listOf(SqsBatchCleanupComponent.EXECUTOR)
            Thread.currentThread().isInterrupted.shouldBeTrue()
            scheduler.shutdownNowCount shouldBeEqualTo 1
            verify(exactly = 0) { client.close() }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `scheduler creation failure becomes one safe manager startup exception`() {
        val secret = "scheduler-${Base58.randomString(16)}"
        val client = mockk<SqsAsyncClient>()

        val error = invoking {
            SqsBatchTransportFactory.create(
                properties = batchProperties(),
                client = client,
                schedulerFactory = { throw IllegalStateException(secret) },
                managerFactory = { _, _, _ -> error("must not create manager") },
                transportFactory = { error("must not create transport") },
            )
        } shouldThrow SqsBatchStartupException::class

        error.startupComponent shouldBeEqualTo SqsBatchStartupComponent.MANAGER
        error.cleanupComponents.shouldBeEmpty()
        error.cause.shouldBeNull()
        error.suppressed.shouldBeEmpty()
        error.toString() shouldNotContain secret
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `transport assembly failure closes manager then scheduler and preserves one safe exception`() {
        val events = mutableListOf<String>()
        val startupSecret = "startup-${Base58.randomString(16)}"
        val managerCleanupSecret = "manager-cleanup-${Base58.randomString(16)}"
        val executorCleanupSecret = "executor-cleanup-${Base58.randomString(16)}"
        val client = mockk<SqsAsyncClient>()
        val manager = mockk<SqsAsyncBatchManager>()
        every { manager.close() } answers {
            events += "manager"
            throw IllegalStateException(managerCleanupSecret)
        }
        val scheduler = TrackingScheduledExecutor(events, executorCleanupSecret)
        val safe = SqsBatchStartupException(
            SqsBatchStartupComponent.TRANSPORT,
            listOf(SqsBatchCleanupComponent.MANAGER, SqsBatchCleanupComponent.EXECUTOR),
        )

        val error = invoking {
            SqsBatchTransportFactory.create(
                properties = batchProperties(),
                client = client,
                schedulerFactory = { scheduler },
                managerFactory = { _, _, _ -> manager },
                transportFactory = { throw IllegalArgumentException(startupSecret) },
                exceptionFactory = { _, _ -> safe },
            )
        } shouldThrow SqsBatchStartupException::class

        error shouldBeSameInstanceAs safe
        events shouldBeEqualTo listOf("manager", "executor")
        error.cleanupComponents shouldBeEqualTo
            listOf(SqsBatchCleanupComponent.MANAGER, SqsBatchCleanupComponent.EXECUTOR)
        error.cause.shouldBeNull()
        error.suppressed.shouldBeEmpty()
        error.toString() shouldNotContain startupSecret
        error.toString() shouldNotContain managerCleanupSecret
        error.toString() shouldNotContain executorCleanupSecret
        verify(exactly = 1) { manager.close() }
        verify(exactly = 0) { client.close() }
        scheduler.shutdownNowCount shouldBeEqualTo 1
    }

    @Test
    fun `scheduler uses daemon threads with fixed non sensitive names`() {
        val secret = Base58.randomString(16)
        val scheduler = newSqsBatchScheduler(1)
        val thread = AtomicReference<Thread>()
        val completed = CountDownLatch(1)

        scheduler.execute {
            thread.set(Thread.currentThread())
            completed.countDown()
        }

        completed.await(5, TimeUnit.SECONDS).shouldBeTrue()
        thread.get().isDaemon.shouldBeTrue()
        thread.get().name shouldStartWith "bluetape4k-sqs-batch-"
        thread.get().name shouldNotContain secret
        scheduler.removeOnCancelPolicy.shouldBeTrue()
        scheduler.shutdownNow()
        scheduler.awaitTermination(5, TimeUnit.SECONDS).shouldBeTrue()
        scheduler.schedule({}, 0, TimeUnit.MILLISECONDS).isCancelled.shouldBeTrue()
    }

    @Test
    fun `manager implementation remains isolated from direct transport class loading`() {
        val loader = ManagerIsolatingClassLoader(javaClass.classLoader)

        val direct = loader.loadClass(DIRECT_TRANSPORT_CLASS)
        direct.declaredConstructors.shouldNotBeEmpty()
        val failure = invoking {
            loader.loadClass(MANAGER_TRANSPORT_CLASS).declaredConstructors
        } shouldThrow NoClassDefFoundError::class

        failure.message.orEmpty() shouldContain MANAGER_SDK_CLASS.replace('.', '/')
    }

    private fun batchProperties(): SqsBatchProperties = SqsBatchProperties(
        enabled = true,
        maxBatchSize = 4,
        flushInterval = Duration.ofMillis(50),
        maxInFlightEntries = 8,
        schedulerThreads = 2,
        shutdownTimeout = Duration.ofSeconds(2),
    )
}

private class TrackingScheduledExecutor(
    private val events: MutableList<String>? = null,
    private val failureMessage: String? = null,
    private val interruptOnAwait: Boolean = false,
) : ScheduledThreadPoolExecutor(1) {
    var shutdownNowCount: Int = 0
        private set

    override fun shutdownNow(): MutableList<Runnable> {
        shutdownNowCount++
        events?.add("executor")
        failureMessage?.let { throw IllegalStateException(it) }
        return super.shutdownNow()
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        if (interruptOnAwait) {
            throw InterruptedException("await-${Base58.randomString(16)}")
        }
        return super.awaitTermination(timeout, unit)
    }
}

private class ManagerIsolatingClassLoader(
    private val source: ClassLoader,
) : ClassLoader(source) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        if (name == MANAGER_SDK_CLASS) {
            throw ClassNotFoundException(name)
        }
        if (name == DIRECT_TRANSPORT_CLASS || name == MANAGER_TRANSPORT_CLASS) {
            findLoadedClass(name) ?: defineLocalClass(name).also { if (resolve) resolveClass(it) }
        } else {
            super.loadClass(name, resolve)
        }
    }

    private fun defineLocalClass(name: String): Class<*> {
        val resourceName = name.replace('.', '/') + ".class"
        val bytes = requireNotNull(source.getResourceAsStream(resourceName)) {
            "class resource must exist"
        }.use { it.readBytes() }
        return defineClass(name, bytes, 0, bytes.size)
    }
}

private const val DIRECT_TRANSPORT_CLASS = "io.bluetape4k.aws.spring.sqs.DirectSqsBatchTransport"
private const val MANAGER_TRANSPORT_CLASS = "io.bluetape4k.aws.spring.sqs.SqsAsyncBatchManagerTransport"
private const val MANAGER_SDK_CLASS = "software.amazon.awssdk.services.sqs.batchmanager.SqsAsyncBatchManager"
