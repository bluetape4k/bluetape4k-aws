package io.bluetape4k.aws.bedrock

import io.bluetape4k.junit5.awaitility.untilSuspending
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
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
    )

    private data class ScenarioResult(
        val publisherCleanupNanos: Long,
        val publisherCancelCount: Int,
        val operationFailure: Throwable? = null,
        val cancellationFailures: List<Throwable> = emptyList(),
    )

    suspend fun run(
        scenario: Scenario,
        eventCount: Int = DEFAULT_EVENT_COUNT,
        failureVolume: Int = DEFAULT_FAILURE_VOLUME,
    ): Sample {
        require(eventCount > 0)
        require(failureVolume >= 0)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "bedrock-runtime-performance")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        return try {
            withContext(dispatcher) {
                runScenario(scenario, eventCount, failureVolume)
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private suspend fun CoroutineScope.runScenario(
        scenario: Scenario,
        eventCount: Int,
        failureVolume: Int,
    ): Sample {
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
            Scenario.NORMAL -> runNormal(handler, operation, collector, eventCount)
            Scenario.COLLECTOR_CANCELLATION -> runCollectorCancellation(handler, collector)
            Scenario.OPERATION_FAILURE -> runOperationFailure(handler, operation, collector, failureVolume)
            Scenario.REPLACEMENT -> runReplacement(handler, operation, collector)
        }

        val late = TimedPublisher<ConverseStreamOutput>()
        handler.onEventStream(late.publisher)
        await.atMost(CLEANUP_TIMEOUT).untilSuspending { late.cancelCount == 1 }
        val pendingCallbackCount = if (late.cancelCount == 1) 0 else 1
        return buildSample(
            scenario = scenario,
            eventCount = eventCount,
            failureVolume = failureVolume,
            startedAt = startedAt,
            terminalFailure = terminalFailure,
            scenarioResult = scenarioResult,
            pendingCallbackCount = pendingCallbackCount,
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
        } catch (cause: Throwable) {
            onFailure(cause)
        }
    }

    private fun buildSample(
        scenario: Scenario,
        eventCount: Int,
        failureVolume: Int,
        startedAt: Long,
        terminalFailure: Throwable?,
        scenarioResult: ScenarioResult,
        pendingCallbackCount: Int,
    ): Sample {
        val coordinatorCleanupNanos = (System.nanoTime() - startedAt).coerceAtLeast(0L)
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
            coordinatorCleanupNanos = coordinatorCleanupNanos,
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
        )
    }

    private suspend fun runNormal(
        handler: ConverseStreamResponseHandler,
        operation: CompletableFuture<Void>,
        collector: Deferred<Unit>,
        eventCount: Int,
    ): ScenarioResult {
        val publisher = TimedPublisher<ConverseStreamOutput>()
        handler.onEventStream(publisher.publisher)
        awaitDemand(publisher, 1)
        repeat(eventCount) { index ->
            awaitDemand(publisher, index + 1)
            publisher.emit(contentDelta("normal-$index"))
        }
        publisher.complete()
        operation.complete(null)
        collector.await()
        return ScenarioResult(publisher.cleanupNanos(), publisher.cancelCount)
    }

    private suspend fun runCollectorCancellation(
        handler: ConverseStreamResponseHandler,
        collector: Deferred<Unit>,
    ): ScenarioResult {
        val publisher = TimedPublisher<ConverseStreamOutput>()
        handler.onEventStream(publisher.publisher)
        awaitDemand(publisher, 1)
        publisher.emit(contentDelta("cancel"))
        collector.await()
        publisher.awaitCleanup()
        return ScenarioResult(publisher.cleanupNanos(), publisher.cancelCount)
    }

    private suspend fun runOperationFailure(
        handler: ConverseStreamResponseHandler,
        operation: CompletableFuture<Void>,
        collector: Deferred<Unit>,
        failureVolume: Int,
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
            TimedPublisher<ConverseStreamOutput> { throw failure }
        }
        publishers.forEachIndexed { index, publisher ->
            handler.onEventStream(publisher.publisher)
            if (index > 0) {
                val previous = publishers[index - 1]
                await.atMost(CLEANUP_TIMEOUT).untilSuspending { previous.cancelCount == 1 }
            }
        }
        val operationFailure = ValidationException.builder().message("operation").build()
        operation.completeExceptionally(operationFailure)
        collector.await()
        publishers.forEach { it.awaitCleanup() }
        return ScenarioResult(
            publisherCleanupNanos = publishers.maxOf { it.cleanupNanos() },
            publisherCancelCount = publishers.sumOf { it.cancelCount },
            operationFailure = operationFailure,
            cancellationFailures = failures,
        )
    }

    private suspend fun runReplacement(
        handler: ConverseStreamResponseHandler,
        operation: CompletableFuture<Void>,
        collector: Deferred<Unit>,
    ): ScenarioResult {
        val first = TimedPublisher<ConverseStreamOutput>()
        val latest = TimedPublisher<ConverseStreamOutput>()
        handler.onEventStream(first.publisher)
        awaitDemand(first, 1)
        handler.onEventStream(latest.publisher)
        first.awaitCleanup()
        awaitDemand(latest, 1)
        latest.emit(contentDelta("replacement"))
        latest.complete()
        operation.complete(null)
        collector.await()
        return ScenarioResult(
            publisherCleanupNanos = first.cleanupNanos().coerceAtLeast(latest.cleanupNanos()),
            publisherCancelCount = first.cancelCount + latest.cancelCount,
        )
    }

    private suspend fun awaitDemand(publisher: TimedPublisher<ConverseStreamOutput>, expected: Int) {
        await.atMost(CLEANUP_TIMEOUT).untilSuspending {
            publisher.requests >= expected
        }
    }

    private fun contentDelta(text: String): ContentBlockDeltaEvent =
        ContentBlockDeltaEvent.builder()
            .delta(ContentBlockDelta.builder().text(text).build())
            .build()

    private class TimedPublisher<T : Any>(
        private val onCancelled: () -> Unit = {},
    ) {
        private val cancelRequestedAt = AtomicLong()
        private val cleanupCompletedAt = AtomicLong()
        private val cleanup = CompletableDeferred<Unit>()
        val publisher = RecordingSdkPublisher<T>(
            onCancelled = {
                cancelRequestedAt.compareAndSet(0L, System.nanoTime())
                try {
                    onCancelled()
                } finally {
                    cleanupCompletedAt.compareAndSet(0L, System.nanoTime())
                    cleanup.complete(Unit)
                }
            },
        )

        val requests: Int
            get() = publisher.requests.sum().toInt()

        val cancelCount: Int
            get() = publisher.cancelCount

        fun emit(value: T) {
            check(publisher.emitOne(value)) { "controlled publisher had no demand" }
        }

        fun complete() = publisher.complete()

        suspend fun awaitCleanup() {
            withContext(kotlinx.coroutines.NonCancellable) {
                await.atMost(CLEANUP_TIMEOUT).untilSuspending { cleanup.isCompleted }
            }
        }

        fun cleanupNanos(): Long {
            val requested = cancelRequestedAt.get()
            val completed = cleanupCompletedAt.get()
            return if (requested == 0L || completed == 0L) 0L else (completed - requested).coerceAtLeast(0L)
        }
    }

    companion object {
        private val CLEANUP_TIMEOUT = Duration.ofSeconds(5)
        private const val DEFAULT_EVENT_COUNT: Int = 8
        private const val DEFAULT_FAILURE_VOLUME: Int = 2
    }
}
