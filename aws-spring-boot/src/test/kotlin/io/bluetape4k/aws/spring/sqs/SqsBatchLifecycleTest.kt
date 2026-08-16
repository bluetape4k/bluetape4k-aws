package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SqsBatchLifecycleTest {

    @Test
    fun `close drains accepted work before manager and executor and rejects new admission`() = runTest {
        val future = CoordinatorCountingFuture<SqsBatchOutcome>()
        val transport = CoordinatorTestTransport().apply { enqueueSend(future) }
        val attached = CompletableFuture<Unit>()
        val hooks = object : SqsBatchCoordinatorHooks {
            override suspend fun afterFutureAttached(token: Long) {
                attached.complete(Unit)
            }
        }
        val events = mutableListOf<String>()
        val resources = resources(transport = transport, events = events)
        val runtime = RecordingCloseRuntime()
        val properties = templateProperties(enabled = true, shutdownTimeout = Duration.ofSeconds(5))
        val template = SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport, hooks),
            resources = resources,
            properties = properties,
            closeRuntime = runtime,
        )
        val entry = sendEntry("drain")
        val operation = async { template.sendMany(listOf(entry)) }
        runCurrent()
        attached.join()

        val closeExecutor = Executors.newSingleThreadExecutor()
        try {
            val closed = CompletableFuture.runAsync(template::close, closeExecutor)
            runtime.firstAwaitEntered.join()
            assertFailsWith<IllegalStateException> { template.sendMany(emptyList()) }
            events.shouldBeEmpty()

            future.complete(sendSuccess(entry)).shouldBeTrue()
            operation.await().successful shouldHaveSize 1
            closed.join()
        } finally {
            closeExecutor.shutdownNow()
        }

        events shouldBeEqualTo listOf("manager", "executor")
        runtime.completionTimeouts.zipWithNext().forEach { (first, second) ->
            second shouldBeLessOrEqualTo first
        }
        assertFailsWith<IllegalStateException> { template.deleteMany(emptyList()) }
    }

    @Test
    fun `one monotonic deadline is reduced across drain manager and executor waits`() = runTest {
        val future = CoordinatorCountingFuture<SqsBatchOutcome>()
        val transport = CoordinatorTestTransport().apply { enqueueSend(future) }
        val attached = CompletableFuture<Unit>()
        val hooks = object : SqsBatchCoordinatorHooks {
            override suspend fun afterFutureAttached(token: Long) {
                attached.complete(Unit)
            }
        }
        val entry = sendEntry("deadline")
        val clock = StepNanoClock(step = 100_000)
        val runtime = RecordingCloseRuntime(
            clock = clock::nanoTime,
            onAwait = { index ->
                if (index == 0) future.complete(sendSuccess(entry))
            },
        )
        val executor = LifecycleExecutor()
        val properties = templateProperties(enabled = true, shutdownTimeout = Duration.ofMillis(1))
        val template = SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport, hooks),
            resources = SqsBatchTransportResources(transport, AutoCloseable {}, executor),
            properties = properties,
            closeRuntime = runtime,
        )
        val operation = async { template.sendMany(listOf(entry)) }
        runCurrent()
        attached.join()

        val closeExecutor = Executors.newSingleThreadExecutor()
        try {
            val closed = CompletableFuture.runAsync(template::close, closeExecutor)
            runtime.firstAwaitEntered.join()
            runCurrent()
            closed.join()
            operation.await()
        } finally {
            closeExecutor.shutdownNow()
        }

        runtime.completionTimeouts.size shouldBeEqualTo 2
        runtime.completionTimeouts[1] shouldBeEqualTo runtime.completionTimeouts[0] - 100_000
        runtime.terminationTimeouts.single() shouldBeEqualTo runtime.completionTimeouts[1] - 100_000
    }

    @Test
    fun `negative nanoTime values preserve the bounded remaining deadline`() {
        val clockValues = listOf(-1_000_000L, -900_000L, -800_000L)
        val clockIndex = AtomicInteger()
        val runtime = RecordingCloseRuntime(clock = { clockValues[clockIndex.getAndIncrement()] })
        val transport = CoordinatorTestTransport()
        val properties = templateProperties(enabled = true, shutdownTimeout = Duration.ofMillis(1))
        val template = SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport),
            resources = SqsBatchTransportResources(transport, AutoCloseable {}, LifecycleExecutor()),
            properties = properties,
            closeRuntime = runtime,
        )

        template.close()

        runtime.completionTimeouts.single() shouldBeEqualTo 900_000L
        runtime.terminationTimeouts.single() shouldBeEqualTo 800_000L
        clockIndex.get() shouldBeEqualTo clockValues.size
    }

    @Test
    fun `timeout cancels accepted future once then closes manager and forces executor shutdown`() = runTest {
        val future = CoordinatorCountingFuture<SqsBatchOutcome>()
        val transport = CoordinatorTestTransport().apply { enqueueSend(future) }
        val attached = CompletableFuture<Unit>()
        val hooks = object : SqsBatchCoordinatorHooks {
            override suspend fun afterFutureAttached(token: Long) {
                attached.complete(Unit)
            }
        }
        val events = mutableListOf<String>()
        val runtime = RecordingCloseRuntime(timeoutAtAwait = 0)
        val properties = templateProperties(enabled = true)
        val template = SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport, hooks),
            resources = resources(transport = transport, events = events, shutdownNowEvent = "executor-now"),
            properties = properties,
            closeRuntime = runtime,
        )
        val operation = async { template.sendMany(listOf(sendEntry("timeout"))) }
        runCurrent()
        attached.join()

        val error = assertFailsWith<SqsBatchCloseException> { template.close() }
        runCurrent()
        operation.await().failed shouldHaveSize 1

        error.components shouldBeEqualTo listOf(SqsBatchCleanupComponent.TIMEOUT)
        future.cancelCount.get() shouldBeEqualTo 1
        events shouldBeEqualTo listOf("manager", "executor-now")
        val repeated = assertFailsWith<SqsBatchCloseException> { template.close() }
        repeated shouldBeSameInstanceAs error
    }

    @Test
    fun `observer uses owner completion without creating another deadline`() = runTest {
        val future = CoordinatorCountingFuture<SqsBatchOutcome>()
        val transport = CoordinatorTestTransport().apply { enqueueSend(future) }
        val attached = CompletableFuture<Unit>()
        val hooks = object : SqsBatchCoordinatorHooks {
            override suspend fun afterFutureAttached(token: Long) {
                attached.complete(Unit)
            }
        }
        val managerSecret = "manager-${Base58.randomString(16)}"
        val runtime = RecordingCloseRuntime()
        val properties = templateProperties(enabled = true, shutdownTimeout = Duration.ofSeconds(5))
        val template = SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport, hooks),
            resources = SqsBatchTransportResources(
                transport,
                AutoCloseable { throw IllegalStateException(managerSecret) },
                LifecycleExecutor(),
            ),
            properties = properties,
            closeRuntime = runtime,
        )
        val entry = sendEntry("observer")
        val operation = async { template.sendMany(listOf(entry)) }
        runCurrent()
        attached.join()

        val closeExecutor = Executors.newFixedThreadPool(2)
        try {
            val owner = CompletableFuture.supplyAsync(
                { assertFailsWith<SqsBatchCloseException> { template.close() } },
                closeExecutor,
            )
            runtime.firstAwaitEntered.join()
            val observerStarted = CountDownLatch(1)
            val observer = CompletableFuture.supplyAsync(
                {
                    observerStarted.countDown()
                    assertFailsWith<SqsBatchCloseException> { template.close() }
                },
                closeExecutor,
            )
            observerStarted.await()
            runtime.nanoTimeCount.get() shouldBeEqualTo 2
            future.complete(sendSuccess(entry)).shouldBeTrue()
            runCurrent()

            val ownerFailure = owner.join()
            val observerFailure = observer.join()
            operation.await().successful shouldHaveSize 1
            observerFailure shouldBeSameInstanceAs ownerFailure
            runtime.completionTimeouts shouldHaveSize 2
            ownerFailure.toString() shouldNotContain managerSecret
        } finally {
            closeExecutor.shutdownNow()
        }
    }

    @Test
    fun `concurrent close callers observe one safe normalized exception instance`() {
        val managerSecret = "manager-${Base58.randomString(16)}"
        val executorSecret = "executor-${Base58.randomString(16)}"
        val transport = CoordinatorTestTransport()
        val executor = LifecycleExecutor(shutdownFailure = executorSecret)
        val resources = SqsBatchTransportResources(
            transport,
            AutoCloseable { throw IllegalStateException(managerSecret) },
            executor,
        )
        val properties = templateProperties(enabled = true)
        val template = SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport),
            resources = resources,
            properties = properties,
        )
        val start = CountDownLatch(1)
        val closeExecutor = Executors.newFixedThreadPool(4)
        try {
            val callers = List(4) {
                CompletableFuture.supplyAsync(
                    {
                        start.await()
                        assertFailsWith<SqsBatchCloseException> { template.close() }
                    },
                    closeExecutor,
                )
            }

            start.countDown()
            val failures = callers.map(CompletableFuture<SqsBatchCloseException>::join)

            failures.forEach { it shouldBeSameInstanceAs failures.first() }
            failures.first().components shouldBeEqualTo
                listOf(SqsBatchCleanupComponent.MANAGER, SqsBatchCleanupComponent.EXECUTOR)
            failures.first().cause.shouldBeNull()
            failures.first().suppressed.shouldBeEmpty()
            failures.first().toString() shouldNotContain managerSecret
            failures.first().toString() shouldNotContain executorSecret
            executor.shutdownCount.get() shouldBeEqualTo 2
        } finally {
            closeExecutor.shutdownNow()
        }
    }

    @Test
    fun `unexpected owner failure still completes shared close outcome once`() {
        val runtimeSecret = "runtime-${Base58.randomString(16)}"
        val clockCalls = AtomicInteger()
        val runtime = RecordingCloseRuntime(
            clock = {
                when (clockCalls.getAndIncrement()) {
                    0 -> 0L
                    1 -> throw AssertionError(runtimeSecret)
                    else -> 0L
                }
            },
        )
        val transport = CoordinatorTestTransport()
        val properties = templateProperties(enabled = true)
        val executor = LifecycleExecutor()
        val template = SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport),
            resources = SqsBatchTransportResources(transport, AutoCloseable {}, executor),
            properties = properties,
            closeRuntime = runtime,
        )

        val first = assertFailsWith<SqsBatchCloseException> { template.close() }
        val repeated = assertFailsWith<SqsBatchCloseException> { template.close() }

        repeated shouldBeSameInstanceAs first
        first.components shouldBeEqualTo listOf(SqsBatchCleanupComponent.TIMEOUT)
        first.toString() shouldNotContain runtimeSecret
        clockCalls.get() shouldBeEqualTo 3
        executor.shutdownCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `owner interrupt is restored while manager and executor cleanup continue`() {
        val managerEntered = CountDownLatch(1)
        val transport = CoordinatorTestTransport()
        val executor = LifecycleExecutor()
        val runtime = RecordingCloseRuntime(
            onAwait = { managerEntered.await() },
            interruptAtAwait = 0,
        )
        val properties = templateProperties(enabled = true)
        val template = SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport),
            resources = SqsBatchTransportResources(
                transport,
                AutoCloseable { managerEntered.countDown() },
                executor,
            ),
            properties = properties,
            closeRuntime = runtime,
        )
        val closeExecutor = Executors.newSingleThreadExecutor()

        try {
            val result = CompletableFuture.supplyAsync(
                {
                    val failure = assertFailsWith<SqsBatchCloseException> { template.close() }
                    failure to Thread.currentThread().isInterrupted
                },
                closeExecutor,
            ).join()

            result.first.components shouldBeEqualTo listOf(SqsBatchCleanupComponent.TIMEOUT)
            result.second.shouldBeTrue()
            executor.shutdownCount.get() shouldBeEqualTo 1
        } finally {
            closeExecutor.shutdownNow()
        }
    }

    @Test
    fun `executor termination failure forces shutdown and becomes timeout`() {
        val events = mutableListOf<String>()
        val transport = CoordinatorTestTransport()
        val executor = LifecycleExecutor(events, terminationResult = false)
        val properties = templateProperties(enabled = true)
        val template = SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport),
            resources = SqsBatchTransportResources(
                transport,
                AutoCloseable { events += "manager" },
                executor,
            ),
            properties = properties,
        )

        val failure = assertFailsWith<SqsBatchCloseException> { template.close() }

        failure.components shouldBeEqualTo listOf(SqsBatchCleanupComponent.TIMEOUT)
        events shouldBeEqualTo listOf("manager", "executor", "executor-now")
        executor.shutdownCount.get() shouldBeEqualTo 2
    }

    @Test
    fun `blocking manager is isolated on a named daemon and timeout still completes close`() {
        val release = CountDownLatch(1)
        val managerEntered = CountDownLatch(1)
        val cleanupThread = AtomicReference<Thread>()
        val manager = AutoCloseable {
            cleanupThread.set(Thread.currentThread())
            managerEntered.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // 의도적으로 interrupt를 무시하는 외부 manager를 재현합니다.
                }
            }
        }
        val transport = CoordinatorTestTransport()
        val runtime = RecordingCloseRuntime(
            onAwait = { managerEntered.await() },
            timeoutAtAwait = 0,
        )
        val properties = templateProperties(enabled = true)
        val template = SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport),
            resources = SqsBatchTransportResources(transport, manager, LifecycleExecutor()),
            properties = properties,
            closeRuntime = runtime,
        )

        try {
            val failure = assertFailsWith<SqsBatchCloseException> { template.close() }
            val repeated = assertFailsWith<SqsBatchCloseException> { template.close() }

            repeated shouldBeSameInstanceAs failure
            failure.components shouldContain SqsBatchCleanupComponent.TIMEOUT
            runtime.managerThreadCount.get() shouldBeEqualTo 1
            cleanupThread.get().isDaemon.shouldBeTrue()
            cleanupThread.get().name shouldContain "bluetape4k-sqs-batch-cleanup-"
            cleanupThread.get().isAlive.shouldBeTrue()
        } finally {
            release.countDown()
            cleanupThread.get()?.join(1_000)
        }
        cleanupThread.get().isAlive.shouldBeFalse()
    }

    @Test
    fun `late manager failure after timeout cannot mutate canonical close outcome`() {
        val managerSecret = "late-manager-${Base58.randomString(16)}"
        val managerEntered = CountDownLatch(1)
        val releaseManager = CountDownLatch(1)
        val cleanupThread = AtomicReference<Thread>()
        val manager = AutoCloseable {
            cleanupThread.set(Thread.currentThread())
            managerEntered.countDown()
            releaseManager.await()
            throw IllegalStateException(managerSecret)
        }
        val transport = CoordinatorTestTransport()
        val runtime = RecordingCloseRuntime(
            onAwait = { managerEntered.await() },
            timeoutAtAwait = 0,
        )
        val properties = templateProperties(enabled = true)
        val template = SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport),
            resources = SqsBatchTransportResources(transport, manager, LifecycleExecutor()),
            properties = properties,
            closeRuntime = runtime,
        )

        val first = assertFailsWith<SqsBatchCloseException> { template.close() }
        first.components shouldBeEqualTo listOf(SqsBatchCleanupComponent.TIMEOUT)
        releaseManager.countDown()
        cleanupThread.get().join(1_000)

        val repeated = assertFailsWith<SqsBatchCloseException> { template.close() }
        repeated shouldBeSameInstanceAs first
        repeated.components shouldBeEqualTo listOf(SqsBatchCleanupComponent.TIMEOUT)
        repeated.toString() shouldNotContain managerSecret
    }

    @Test
    fun `template assembly rollback closes manager then executor and preserves one safe startup exception`() {
        val startupSecret = "startup-${Base58.randomString(16)}"
        val managerSecret = "manager-${Base58.randomString(16)}"
        val executorSecret = "executor-${Base58.randomString(16)}"
        val events = mutableListOf<String>()
        val client = mockk<SqsAsyncClient>()
        val transport = CoordinatorTestTransport()
        val executor = LifecycleExecutor(events, shutdownNowFailure = executorSecret)
        val resources = SqsBatchTransportResources(
            transport,
            AutoCloseable {
                events += "manager"
                throw IllegalStateException(managerSecret)
            },
            executor,
        )
        val safe = SqsBatchStartupException(
            SqsBatchStartupComponent.TEMPLATE,
            listOf(SqsBatchCleanupComponent.MANAGER, SqsBatchCleanupComponent.EXECUTOR),
        )

        val failure = assertFailsWith<SqsBatchStartupException> {
            SqsBatchCoroutinesTemplate.create(
                client = client,
                properties = templateProperties(enabled = true),
                resourcesFactory = { _, _ -> resources },
                templateFactory = { _, _, _, _ -> throw IllegalStateException(startupSecret) },
                exceptionFactory = { _, _ -> safe },
            )
        }

        failure shouldBeSameInstanceAs safe
        events shouldBeEqualTo listOf("manager", "executor-now")
        failure.cause.shouldBeNull()
        failure.suppressed.shouldBeEmpty()
        failure.toString() shouldNotContain startupSecret
        failure.toString() shouldNotContain managerSecret
        failure.toString() shouldNotContain executorSecret
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `template assembly rollback bounds blocking manager and always forces executor shutdown`() {
        val startupSecret = "startup-${Base58.randomString(16)}"
        val managerEntered = CountDownLatch(1)
        val releaseManager = CountDownLatch(1)
        val cleanupThread = AtomicReference<Thread>()
        val client = mockk<SqsAsyncClient>()
        val transport = CoordinatorTestTransport()
        val executor = LifecycleExecutor()
        val resources = SqsBatchTransportResources(
            transport,
            AutoCloseable {
                cleanupThread.set(Thread.currentThread())
                managerEntered.countDown()
                while (releaseManager.count > 0) {
                    try {
                        releaseManager.await()
                    } catch (_: InterruptedException) {
                        // 외부 manager가 interrupt를 무시하는 startup rollback을 재현합니다.
                    }
                }
            },
            executor,
        )
        val runtime = RecordingCloseRuntime(
            onAwait = { managerEntered.await() },
            timeoutAtAwait = 0,
        )
        val safe = AtomicReference<SqsBatchStartupException>()

        try {
            val failure = assertFailsWith<SqsBatchStartupException> {
                SqsBatchCoroutinesTemplate.create(
                    client = client,
                    properties = templateProperties(enabled = true),
                    closeRuntime = runtime,
                    resourcesFactory = { _, _ -> resources },
                    templateFactory = { _, _, _, _ -> throw IllegalStateException(startupSecret) },
                    exceptionFactory = { component, cleanup ->
                        SqsBatchStartupException(component, cleanup).also(safe::set)
                    },
                )
            }

            failure shouldBeSameInstanceAs safe.get()
            failure.cleanupComponents shouldBeEqualTo listOf(SqsBatchCleanupComponent.TIMEOUT)
            cleanupThread.get().isDaemon.shouldBeTrue()
            executor.shutdownCount.get() shouldBeEqualTo 1
            failure.toString() shouldNotContain startupSecret
            verify(exactly = 0) { client.close() }
        } finally {
            releaseManager.countDown()
            cleanupThread.get()?.join(1_000)
        }
        cleanupThread.get().isAlive.shouldBeFalse()
    }

    @Test
    fun `template assembly rollback normalizes runtime clock failure and still shuts down executor`() {
        val startupSecret = "startup-${Base58.randomString(16)}"
        val runtimeSecret = "runtime-${Base58.randomString(16)}"
        val client = mockk<SqsAsyncClient>()
        val transport = CoordinatorTestTransport()
        val executor = LifecycleExecutor()
        val runtime = RecordingCloseRuntime(clock = { throw AssertionError(runtimeSecret) })
        val safe = AtomicReference<SqsBatchStartupException>()

        val failure = assertFailsWith<SqsBatchStartupException> {
            SqsBatchCoroutinesTemplate.create(
                client = client,
                properties = templateProperties(enabled = true),
                closeRuntime = runtime,
                resourcesFactory = { _, _ ->
                    SqsBatchTransportResources(transport, AutoCloseable {}, executor)
                },
                templateFactory = { _, _, _, _ -> throw IllegalStateException(startupSecret) },
                exceptionFactory = { component, cleanup ->
                    SqsBatchStartupException(component, cleanup).also(safe::set)
                },
            )
        }

        failure shouldBeSameInstanceAs safe.get()
        failure.cleanupComponents shouldBeEqualTo listOf(SqsBatchCleanupComponent.TIMEOUT)
        executor.shutdownCount.get() shouldBeEqualTo 1
        failure.toString() shouldNotContain startupSecret
        failure.toString() shouldNotContain runtimeSecret
        verify(exactly = 0) { client.close() }
    }

    private fun resources(
        transport: SqsBatchTransport,
        events: MutableList<String>,
        shutdownNowEvent: String = "executor-now",
    ): SqsBatchTransportResources {
        return SqsBatchTransportResources(
            transport,
            AutoCloseable { events += "manager" },
            LifecycleExecutor(events, shutdownEvent = "executor", shutdownNowEvent = shutdownNowEvent),
        )
    }
}

private class RecordingCloseRuntime(
    private val clock: () -> Long = System::nanoTime,
    private val onAwait: (Int) -> Unit = {},
    private val timeoutAtAwait: Int? = null,
    private val interruptAtAwait: Int? = null,
) : SqsBatchCloseRuntime {
    val completionTimeouts = mutableListOf<Long>()
    val terminationTimeouts = mutableListOf<Long>()
    val firstAwaitEntered = CompletableFuture<Unit>()
    val managerThreadCount = AtomicInteger()
    val nanoTimeCount = AtomicInteger()
    private val awaitCount = AtomicInteger()

    override fun nanoTime(): Long {
        nanoTimeCount.incrementAndGet()
        return clock()
    }

    override fun awaitCompletion(future: CompletableFuture<*>, timeoutNanos: Long) {
        val index = awaitCount.getAndIncrement()
        completionTimeouts += timeoutNanos
        onAwait(index)
        firstAwaitEntered.complete(Unit)
        if (interruptAtAwait == index) throw InterruptedException("controlled interrupt")
        if (timeoutAtAwait == index) throw TimeoutException("controlled timeout")
        future.join()
    }

    override fun awaitTermination(executor: ExecutorService, timeoutNanos: Long): Boolean {
        terminationTimeouts += timeoutNanos
        return executor.awaitTermination(timeoutNanos, TimeUnit.NANOSECONDS)
    }

    override fun newManagerCleanupThread(task: Runnable): Thread {
        managerThreadCount.incrementAndGet()
        return Thread(task, "bluetape4k-sqs-batch-cleanup-test").apply { isDaemon = true }
    }
}

private class StepNanoClock(
    private val step: Long,
) {
    private val current = AtomicReference(0L)

    fun nanoTime(): Long = current.getAndUpdate { it + step }
}

private class LifecycleExecutor(
    private val events: MutableList<String>? = null,
    private val shutdownEvent: String = "executor",
    private val shutdownNowEvent: String = "executor-now",
    private val shutdownFailure: String? = null,
    private val shutdownNowFailure: String? = null,
    private val terminationResult: Boolean = true,
) : ScheduledThreadPoolExecutor(1) {
    val shutdownCount = AtomicInteger()

    override fun shutdown() {
        shutdownCount.incrementAndGet()
        events?.add(shutdownEvent)
        shutdownFailure?.let { throw IllegalStateException(it) }
        super.shutdown()
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdownCount.incrementAndGet()
        events?.add(shutdownNowEvent)
        shutdownNowFailure?.let { throw IllegalStateException(it) }
        return super.shutdownNow()
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = terminationResult
}
