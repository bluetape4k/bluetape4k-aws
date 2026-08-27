package io.bluetape4k.aws.bedrock

import io.bluetape4k.junit5.awaitility.untilSuspending
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.awaitility.kotlin.await
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 실제 AWS endpoint 없이 ConverseStream callback 경계를 측정하는 adapter입니다.
 *
 * 이 adapter는 절대 latency나 외부 publisher 성능을 주장하지 않습니다. publisher가
 * block하거나 지연 cleanup하는 조건과 장기 heap/throughput 측정은 #506의 범위입니다.
 */
internal class BedrockRuntimePerformanceRuntimeAdapter {

    enum class Scenario {
        NORMAL,
        COLLECTOR_CANCELLATION,
        OPERATION_FAILURE,
        REPLACEMENT,
    }

    enum class CleanupMode {
        IMMEDIATE,
        DELAYED,
        BLOCKING,
    }

    data class Sample(
        val scenario: Scenario,
        val eventCount: Int,
        val failureVolume: Int,
        val coordinatorCleanupNanos: Long,
        val publisherCleanupNanos: Long,
        val publisherCancelCount: Int,
        val pendingCallbackCount: Int,
        val operationFailureIsPrimary: Boolean = false,
        val retainedSuppressedCount: Int = 0,
        val overflowMarkerCount: Int = 0,
        val overflowDroppedCount: Long = 0,
        val markerRetainsOriginalThrowable: Boolean = false,
        val duplicateIdentityCount: Int = 0,
        val cleanupMode: CleanupMode = CleanupMode.IMMEDIATE,
        val watchdogReleaseCount: Int = 0,
        val blockingWaitNanos: Long = 0,
        val allocatedBytes: Long = 0,
        val heapUsedBefore: Long = 0,
        val heapUsedAfter: Long = 0,
        val heapDeltaBytes: Long = 0,
        val throughputEventsPerSecond: Double = 0.0,
    )

    data class LongRunResult(
        val samples: List<Sample>,
        val eventCount: Int,
        val measurementIterations: Int,
        val throughputEventsPerSecond: Double,
        val allocatedBytes: Long,
        val heapUsedBefore: Long,
        val heapUsedAfter: Long,
        val heapDeltaBytes: Long,
        val pendingCallbackCount: Int,
        val operationFailureIsPrimary: Boolean,
        val retainedSuppressedCount: Int,
        val overflowMarkerCount: Int,
        val overflowDroppedCount: Long,
        val markerRetainsOriginalThrowable: Boolean,
    )

    private data class ScenarioResult(
        val coordinatorCleanupNanos: Long,
        val publisherCleanupNanos: Long,
        val publisherCancelCount: Int,
        val watchdogReleaseCount: Int,
        val blockingWaitNanos: Long,
        val operationFailure: Throwable? = null,
        val cancellationFailures: List<Throwable> = emptyList(),
    )

    private val allocationBean: com.sun.management.ThreadMXBean by lazy {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            ?: error("ThreadMXBean allocated memory is unavailable")
        require(bean.isThreadAllocatedMemorySupported) {
            "ThreadMXBean allocated memory is unavailable"
        }
        if (!bean.isThreadAllocatedMemoryEnabled) {
            bean.isThreadAllocatedMemoryEnabled = true
        }
        bean
    }

    suspend fun run(
        scenario: Scenario,
        eventCount: Int = DEFAULT_EVENT_COUNT,
        failureVolume: Int = DEFAULT_FAILURE_VOLUME,
    ): Sample = run(
        scenario = scenario,
        cleanupMode = CleanupMode.IMMEDIATE,
        eventCount = eventCount,
        failureVolume = failureVolume,
    )

    suspend fun run(
        scenario: Scenario,
        cleanupMode: CleanupMode,
        eventCount: Int = DEFAULT_EVENT_COUNT,
        failureVolume: Int = DEFAULT_FAILURE_VOLUME,
    ): Sample {
        require(eventCount > 0)
        require(failureVolume >= 0)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "bedrock-runtime-performance")
        }
        val scheduler = Executors.newScheduledThreadPool(2) { runnable ->
            Thread(runnable, "bedrock-runtime-cleanup").apply { isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        return try {
            withContext(dispatcher) {
                runScenario(scenario, cleanupMode, eventCount, failureVolume, scheduler)
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    suspend fun runLongRun(
        eventCount: Int = DEFAULT_LONG_RUN_EVENT_COUNT,
        measurementIterations: Int = DEFAULT_LONG_RUN_MEASUREMENT_ITERATIONS,
    ): LongRunResult {
        require(eventCount > 0)
        require(measurementIterations > 0)
        run(
            scenario = Scenario.NORMAL,
            cleanupMode = CleanupMode.IMMEDIATE,
            eventCount = eventCount,
        )
        val samples = buildList {
            repeat(measurementIterations) {
                add(
                    run(
                        scenario = Scenario.NORMAL,
                        cleanupMode = CleanupMode.IMMEDIATE,
                        eventCount = eventCount,
                    ),
                )
            }
            add(
                run(
                    scenario = Scenario.OPERATION_FAILURE,
                    cleanupMode = CleanupMode.IMMEDIATE,
                    eventCount = eventCount,
                    failureVolume = DEFAULT_LONG_RUN_FAILURE_VOLUME,
                ),
            )
        }
        val normalSamples = samples.dropLast(1)
        val totalEvents = eventCount.toLong() * measurementIterations
        val totalElapsedNanos = normalSamples.sumOf { it.coordinatorCleanupNanos }
        val throughput = if (totalElapsedNanos > 0L) {
            totalEvents.toDouble() * NANOS_PER_SECOND / totalElapsedNanos
        } else {
            0.0
        }
        val retention = samples.last()
        return LongRunResult(
            samples = samples,
            eventCount = eventCount,
            measurementIterations = measurementIterations,
            throughputEventsPerSecond = throughput,
            allocatedBytes = samples.sumOf { it.allocatedBytes },
            heapUsedBefore = samples.first().heapUsedBefore,
            heapUsedAfter = samples.last().heapUsedAfter,
            heapDeltaBytes = samples.last().heapUsedAfter - samples.first().heapUsedBefore,
            pendingCallbackCount = samples.maxOf { it.pendingCallbackCount },
            operationFailureIsPrimary = retention.operationFailureIsPrimary,
            retainedSuppressedCount = retention.retainedSuppressedCount,
            overflowMarkerCount = retention.overflowMarkerCount,
            overflowDroppedCount = retention.overflowDroppedCount,
            markerRetainsOriginalThrowable = retention.markerRetainsOriginalThrowable,
        )
    }

    @Suppress("LongMethod")
    private suspend fun CoroutineScope.runScenario(
        scenario: Scenario,
        cleanupMode: CleanupMode,
        eventCount: Int,
        failureVolume: Int,
        scheduler: ScheduledExecutorService,
    ): Sample {
        val operationThreadId = Thread.currentThread().threadId()
        val allocationBefore = allocatedBytes(operationThreadId)
        val heapBefore = usedHeap()
        val client = mockk<BedrockRuntimeAsyncClient>()
        val request = ConverseStreamRequest.builder()
            .modelId("performance-model")
            .build()
        val operation = CompletableFuture<Void>()
        val handlerReady = CompletableDeferred<ConverseStreamResponseHandler>()
        every { client.converseStream(request, any<ConverseStreamResponseHandler>()) } answers {
            handlerReady.complete(secondArg())
            operation
        }

        var terminalFailure: Throwable? = null
        val startedAt = System.nanoTime()
        val collector = startCollector(client, request, scenario) { cause -> terminalFailure = cause }
        val handler = handlerReady.await()
        val scenarioResult = when (scenario) {
            Scenario.NORMAL -> runNormal(
                handler,
                operation,
                collector,
                eventCount,
                cleanupMode,
                scheduler,
                startedAt,
            )
            Scenario.COLLECTOR_CANCELLATION -> runCollectorCancellation(
                handler,
                collector,
                cleanupMode,
                scheduler,
                startedAt,
            )
            Scenario.OPERATION_FAILURE -> runOperationFailure(
                handler,
                operation,
                collector,
                failureVolume,
                cleanupMode,
                scheduler,
                startedAt,
            )
            Scenario.REPLACEMENT -> runReplacement(
                handler,
                operation,
                collector,
                cleanupMode,
                scheduler,
                startedAt,
            )
        }

        val late = TimedPublisher<ConverseStreamOutput>(cleanupMode, scheduler)
        handler.onEventStream(late.publisher)
        late.awaitCleanup()
        val pendingCallbackCount = if (late.cancelCount == 1) 0 else 1
        val allocationAfter = allocatedBytes(operationThreadId)
        val heapAfter = usedHeap()
        return buildSample(
            scenario = scenario,
            eventCount = eventCount,
            failureVolume = failureVolume,
            cleanupMode = cleanupMode,
            terminalFailure = terminalFailure,
            scenarioResult = scenarioResult,
            pendingCallbackCount = pendingCallbackCount,
            allocatedBytes = (allocationAfter - allocationBefore).coerceAtLeast(0L),
            heapUsedBefore = heapBefore,
            heapUsedAfter = heapAfter,
        )
    }

    private fun CoroutineScope.startCollector(
        client: BedrockRuntimeAsyncClient,
        request: ConverseStreamRequest,
        scenario: Scenario,
        onFailure: (Throwable) -> Unit,
    ): Deferred<Unit> = async {
        try {
            if (scenario == Scenario.COLLECTOR_CANCELLATION) {
                client.converseStreamFlow(request).first()
            } else {
                client.converseStreamFlow(request).toList()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (cause: Throwable) {
            onFailure(cause)
        }
    }

    private fun buildSample(
        scenario: Scenario,
        eventCount: Int,
        failureVolume: Int,
        cleanupMode: CleanupMode,
        terminalFailure: Throwable?,
        scenarioResult: ScenarioResult,
        pendingCallbackCount: Int,
        allocatedBytes: Long,
        heapUsedBefore: Long,
        heapUsedAfter: Long,
    ): Sample {
        val failure = terminalFailure
        val marker = failure?.suppressed?.firstOrNull {
            it.message?.startsWith("suppressed failure count") == true
        }
        val retainedRoots = failure?.suppressed.orEmpty().count { it !== marker }
        val duplicateIdentityCount = failure?.suppressed.orEmpty()
            .count { it === scenarioResult.cancellationFailures.lastOrNull() }
        val overflowDroppedCount = marker?.message
            ?.substringAfter("dropped=", "0")
            ?.toLongOrNull()
            ?: 0L
        return Sample(
            scenario = scenario,
            eventCount = eventCount,
            failureVolume = failureVolume,
            coordinatorCleanupNanos = scenarioResult.coordinatorCleanupNanos,
            publisherCleanupNanos = scenarioResult.publisherCleanupNanos,
            publisherCancelCount = scenarioResult.publisherCancelCount,
            pendingCallbackCount = pendingCallbackCount,
            operationFailureIsPrimary = scenarioResult.operationFailure != null &&
                failure === scenarioResult.operationFailure,
            retainedSuppressedCount = retainedRoots,
            overflowMarkerCount = if (marker == null) 0 else 1,
            overflowDroppedCount = overflowDroppedCount,
            markerRetainsOriginalThrowable = marker?.let { markerFailure ->
                markerFailure.cause != null || markerFailure.suppressed.any { markerSuppressed ->
                    scenarioResult.cancellationFailures.any { original -> original === markerSuppressed }
                }
            } ?: false,
            duplicateIdentityCount = duplicateIdentityCount,
            cleanupMode = cleanupMode,
            watchdogReleaseCount = scenarioResult.watchdogReleaseCount,
            blockingWaitNanos = scenarioResult.blockingWaitNanos,
            allocatedBytes = allocatedBytes,
            heapUsedBefore = heapUsedBefore,
            heapUsedAfter = heapUsedAfter,
            heapDeltaBytes = heapUsedAfter - heapUsedBefore,
            throughputEventsPerSecond = if (scenarioResult.coordinatorCleanupNanos > 0L) {
                eventCount.toDouble() * NANOS_PER_SECOND / scenarioResult.coordinatorCleanupNanos
            } else {
                0.0
            },
        )
    }

    private suspend fun runNormal(
        handler: ConverseStreamResponseHandler,
        operation: CompletableFuture<Void>,
        collector: Deferred<Unit>,
        eventCount: Int,
        cleanupMode: CleanupMode,
        scheduler: ScheduledExecutorService,
        startedAt: Long,
    ): ScenarioResult {
        val publisher = TimedPublisher<ConverseStreamOutput>(cleanupMode, scheduler)
        handler.onEventStream(publisher.publisher)
        awaitDemand(publisher, 1)
        repeat(eventCount) { index ->
            awaitDemand(publisher, index + 1)
            publisher.emit(contentDelta("normal-$index"))
        }
        publisher.complete()
        operation.complete(null)
        collector.await()
        val coordinatorCleanupNanos = elapsedSince(startedAt)
        publisher.awaitCleanup()
        return ScenarioResult(
            coordinatorCleanupNanos = coordinatorCleanupNanos,
            publisherCleanupNanos = publisher.cleanupNanos(),
            publisherCancelCount = publisher.cancelCount,
            watchdogReleaseCount = publisher.watchdogReleaseCount,
            blockingWaitNanos = publisher.blockingWaitNanos,
        )
    }

    private suspend fun runCollectorCancellation(
        handler: ConverseStreamResponseHandler,
        collector: Deferred<Unit>,
        cleanupMode: CleanupMode,
        scheduler: ScheduledExecutorService,
        startedAt: Long,
    ): ScenarioResult {
        val publisher = TimedPublisher<ConverseStreamOutput>(cleanupMode, scheduler)
        handler.onEventStream(publisher.publisher)
        awaitDemand(publisher, 1)
        publisher.emit(contentDelta("cancel"))
        collector.await()
        val coordinatorCleanupNanos = elapsedSince(startedAt)
        publisher.awaitCleanup()
        return ScenarioResult(
            coordinatorCleanupNanos = coordinatorCleanupNanos,
            publisherCleanupNanos = publisher.cleanupNanos(),
            publisherCancelCount = publisher.cancelCount,
            watchdogReleaseCount = publisher.watchdogReleaseCount,
            blockingWaitNanos = publisher.blockingWaitNanos,
        )
    }

    private suspend fun runOperationFailure(
        handler: ConverseStreamResponseHandler,
        operation: CompletableFuture<Void>,
        collector: Deferred<Unit>,
        failureVolume: Int,
        cleanupMode: CleanupMode,
        scheduler: ScheduledExecutorService,
        startedAt: Long,
    ): ScenarioResult {
        val duplicateFailure = IllegalStateException("duplicate-cancel")
        val failures = buildList {
            repeat((failureVolume - 2).coerceAtLeast(0)) { index ->
                add(IllegalStateException("cancel-$index"))
            }
            add(duplicateFailure)
            add(duplicateFailure)
        }.take(failureVolume.coerceAtLeast(2))
        val publishers = failures.map { failure ->
            TimedPublisher<ConverseStreamOutput>(cleanupMode, scheduler) { throw failure }
        }
        publishers.forEachIndexed { index, publisher ->
            handler.onEventStream(publisher.publisher)
            if (index > 0) {
                val previous = publishers[index - 1]
                await.atMost(CLEANUP_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .pollDelay(POLL_INTERVAL)
                    .untilSuspending { previous.cancelCount == 1 }
            }
        }
        val operationFailure = ValidationException.builder().message("operation").build()
        operation.completeExceptionally(operationFailure)
        collector.await()
        val coordinatorCleanupNanos = elapsedSince(startedAt)
        publishers.forEach { it.awaitCleanup() }
        return ScenarioResult(
            coordinatorCleanupNanos = coordinatorCleanupNanos,
            publisherCleanupNanos = publishers.maxOf { it.cleanupNanos() },
            publisherCancelCount = publishers.sumOf { it.cancelCount },
            watchdogReleaseCount = publishers.sumOf { it.watchdogReleaseCount },
            blockingWaitNanos = publishers.sumOf { it.blockingWaitNanos },
            operationFailure = operationFailure,
            cancellationFailures = failures,
        )
    }

    private suspend fun runReplacement(
        handler: ConverseStreamResponseHandler,
        operation: CompletableFuture<Void>,
        collector: Deferred<Unit>,
        cleanupMode: CleanupMode,
        scheduler: ScheduledExecutorService,
        startedAt: Long,
    ): ScenarioResult {
        val first = TimedPublisher<ConverseStreamOutput>(cleanupMode, scheduler)
        val latest = TimedPublisher<ConverseStreamOutput>(cleanupMode, scheduler)
        handler.onEventStream(first.publisher)
        awaitDemand(first, 1)
        handler.onEventStream(latest.publisher)
        first.awaitCleanup()
        awaitDemand(latest, 1)
        latest.emit(contentDelta("replacement"))
        latest.complete()
        operation.complete(null)
        collector.await()
        val coordinatorCleanupNanos = elapsedSince(startedAt)
        latest.awaitCleanup()
        return ScenarioResult(
            coordinatorCleanupNanos = coordinatorCleanupNanos,
            publisherCleanupNanos = first.cleanupNanos().coerceAtLeast(latest.cleanupNanos()),
            publisherCancelCount = first.cancelCount + latest.cancelCount,
            watchdogReleaseCount = first.watchdogReleaseCount + latest.watchdogReleaseCount,
            blockingWaitNanos = first.blockingWaitNanos + latest.blockingWaitNanos,
        )
    }

    private fun elapsedSince(startedAt: Long): Long =
        (System.nanoTime() - startedAt).coerceAtLeast(1L)

    private fun allocatedBytes(threadId: Long): Long =
        allocationBean.getThreadAllocatedBytes(threadId).coerceAtLeast(0L)

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
    }

    private suspend fun awaitDemand(publisher: TimedPublisher<ConverseStreamOutput>, expected: Int) {
        await.atMost(CLEANUP_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .pollDelay(POLL_INTERVAL)
            .untilSuspending {
                publisher.requests >= expected
            }
    }

    private fun contentDelta(text: String): ContentBlockDeltaEvent =
        ContentBlockDeltaEvent.builder()
            .delta(ContentBlockDelta.builder().text(text).build())
            .build()

    private class TimedPublisher<T : Any>(
        private val cleanupMode: CleanupMode,
        private val scheduler: ScheduledExecutorService,
        private val onCancelled: () -> Unit = {},
    ) {
        private val cancelRequestedAt = AtomicLong()
        private val cleanupCompletedAt = AtomicLong()
        private val blockingWaitNanosValue = AtomicLong()
        private val watchdogReleaseCountValue = AtomicInteger()
        private val cleanup = CompletableDeferred<Unit>()
        private val blockingRelease = CountDownLatch(1)
        val publisher = RecordingSdkPublisher<T>(
            onCancelled = {
                cancelRequestedAt.compareAndSet(0L, System.nanoTime())
                var callbackFailure: Throwable? = null
                try {
                    onCancelled()
                } catch (cause: Throwable) {
                    callbackFailure = cause
                } finally {
                    try {
                        completeAccordingToMode()
                    } catch (cause: Throwable) {
                        callbackFailure = callbackFailure ?: cause
                    }
                }
                callbackFailure?.let { throw it }
            },
        )

        val requests: Int
            get() = publisher.requests.sum().toInt()

        val cancelCount: Int
            get() = publisher.cancelCount

        val watchdogReleaseCount: Int
            get() = watchdogReleaseCountValue.get()

        val blockingWaitNanos: Long
            get() = blockingWaitNanosValue.get()

        fun emit(value: T) {
            check(publisher.emitOne(value)) { "controlled publisher had no demand" }
        }

        fun complete() = publisher.complete()

        suspend fun awaitCleanup() {
            withContext(kotlinx.coroutines.NonCancellable) {
                await.atMost(CLEANUP_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .pollDelay(POLL_INTERVAL)
                    .untilSuspending { cleanup.isCompleted }
            }
        }

        private fun completeAccordingToMode() {
            when (cleanupMode) {
                CleanupMode.IMMEDIATE -> completeCleanup()
                CleanupMode.DELAYED -> scheduler.schedule(
                    { completeCleanup() },
                    CLEANUP_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                CleanupMode.BLOCKING -> {
                    val waitStartedAt = System.nanoTime()
                    val watchdog = scheduler.schedule(
                        {
                            watchdogReleaseCountValue.incrementAndGet()
                            blockingRelease.countDown()
                        },
                        BLOCKING_WATCHDOG_DELAY_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                    try {
                        check(
                            blockingRelease.await(
                                BLOCKING_TIMEOUT_MILLIS,
                                TimeUnit.MILLISECONDS,
                            ),
                        ) { "blocking cleanup watchdog did not release publisher" }
                    } finally {
                        blockingWaitNanosValue.addAndGet(
                            (System.nanoTime() - waitStartedAt).coerceAtLeast(0L),
                        )
                        watchdog.cancel(false)
                    }
                    completeCleanup()
                }
            }
        }

        private fun completeCleanup() {
            cleanupCompletedAt.compareAndSet(0L, System.nanoTime())
            cleanup.complete(Unit)
        }

        fun cleanupNanos(): Long {
            val requested = cancelRequestedAt.get()
            val completed = cleanupCompletedAt.get()
            return if (requested == 0L || completed == 0L) 0L else (completed - requested).coerceAtLeast(0L)
        }
    }

    companion object {
        private val CLEANUP_TIMEOUT = Duration.ofSeconds(5)
        private val POLL_INTERVAL = Duration.ofMillis(1)
        private const val DEFAULT_EVENT_COUNT: Int = 8
        private const val DEFAULT_FAILURE_VOLUME: Int = 2
        private const val DEFAULT_LONG_RUN_EVENT_COUNT: Int = 256
        private const val DEFAULT_LONG_RUN_MEASUREMENT_ITERATIONS: Int = 4
        private const val DEFAULT_LONG_RUN_FAILURE_VOLUME: Int = 20
        private const val CLEANUP_DELAY_MILLIS: Long = 25
        private const val BLOCKING_WATCHDOG_DELAY_MILLIS: Long = 25
        private const val BLOCKING_TIMEOUT_MILLIS: Long = 5_000
        private const val NANOS_PER_SECOND: Double = 1_000_000_000.0
    }
}
