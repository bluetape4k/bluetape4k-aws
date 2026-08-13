package io.bluetape4k.aws.bedrock

import io.bluetape4k.aws.bedrock.model.converseStreamRequestOf
import io.bluetape4k.aws.bedrock.model.textDeltaOrNull
import io.bluetape4k.coroutines.flow.extensions.castNotNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import software.amazon.awssdk.core.async.SdkPublisher
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponse
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.Message
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as withReentrantLock

private sealed interface StreamTerminal {
    data object Active : StreamTerminal
    data object Completed : StreamTerminal
    data class Failed(val cause: Throwable) : StreamTerminal
    data object Cancelled : StreamTerminal
}

private sealed interface AttemptCompletion {
    data object Succeeded : AttemptCompletion
    data class Failed(val cause: Throwable) : AttemptCompletion
    data class Cancelled(val cleanupFailure: Throwable?) : AttemptCompletion
}

private const val MAX_RETAINED_SUPPRESSED_FAILURES = 16
private const val MAX_OVERFLOW_COUNT = Long.MAX_VALUE

private data class FailureSnapshot(
    val primary: Throwable?,
    val suppressed: List<Throwable>,
    val overflowCount: Long,
)

private class SuppressedFailureOverflow(count: Long) :
    RuntimeException("suppressed failure count exceeded bound; dropped=$count") {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Callback and terminal failure aggregation is intentionally bounded.
 *
 * The coordinator owns instances of this class behind its callback lock. A local instance
 * used while rejecting or handing off one publisher has one owner and therefore needs no
 * additional synchronization.
 */
private class BoundedFailureAccumulator(initialPrimary: Throwable? = null) {
    private var primary: Throwable? = initialPrimary
    private val suppressed = ArrayList<Throwable>(MAX_RETAINED_SUPPRESSED_FAILURES)
    private var overflowCount = 0L
    private val retainedIdentities =
        Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    private var materialized: Throwable? = null

    init {
        initialPrimary?.let(retainedIdentities::add)
    }

    fun record(failure: Throwable?) {
        check(materialized == null) { "failure accumulator is already materialized" }
        if (failure == null || failure is CancellationException || retainedIdentities.contains(failure)) return
        if (primary == null) {
            primary = failure
            retainedIdentities += failure
        } else if (suppressed.size < MAX_RETAINED_SUPPRESSED_FAILURES) {
            suppressed += failure
            retainedIdentities += failure
        } else {
            overflowCount = saturatingAdd(overflowCount, 1)
        }
    }

    /** Selects an authoritative operation, publisher, or collector cause. */
    fun selectPrimary(authoritative: Throwable?) {
        check(materialized == null) { "failure accumulator is already materialized" }
        if (authoritative == null || primary === authoritative) return

        val existingIndex = suppressed.indexOfFirst { it === authoritative }
        if (primary == null) {
            primary = authoritative
            retainedIdentities += authoritative
            return
        }

        val previous = primary
        if (existingIndex >= 0) {
            suppressed.removeAt(existingIndex)
            retainedIdentities.remove(authoritative)
        }
        primary = authoritative
        retainedIdentities += authoritative
        if (previous != null) {
            suppressed.add(0, previous)
            retainedIdentities += previous
            if (suppressed.size > MAX_RETAINED_SUPPRESSED_FAILURES) {
                val dropped = suppressed.removeAt(suppressed.lastIndex)
                retainedIdentities.remove(dropped)
                overflowCount = saturatingAdd(overflowCount, 1)
            }
        }
    }

    fun merge(snapshot: FailureSnapshot) {
        record(snapshot.primary)
        snapshot.suppressed.forEach(::record)
        overflowCount = saturatingAdd(overflowCount, snapshot.overflowCount)
    }

    fun snapshotAndClear(): FailureSnapshot {
        check(materialized == null) { "failure accumulator is already materialized" }
        return FailureSnapshot(primary, suppressed.toList(), overflowCount).also {
            primary = null
            suppressed.clear()
            retainedIdentities.clear()
            overflowCount = 0
        }
    }

    fun throwable(): Throwable? {
        if (materialized == null) {
            primary?.let { current ->
                suppressed.forEach(current::addSuppressed)
                if (overflowCount > 0) current.addSuppressed(SuppressedFailureOverflow(overflowCount))
                materialized = current
            }
        }
        return materialized
    }

    private fun saturatingAdd(current: Long, increment: Long): Long =
        if (increment > MAX_OVERFLOW_COUNT - current) MAX_OVERFLOW_COUNT else current + increment
}

private data class CallbackCompletion(
    val sequence: Long,
    val result: CompletableDeferred<FailureSnapshot?> = CompletableDeferred(),
    var logicallyCompleted: Boolean = false,
    var drainClaimed: Boolean = false,
)

private data class CallbackDrain(
    val pending: List<CallbackCompletion>,
    val completedFailure: FailureSnapshot,
)

private class StreamAttempt<T : Any>(
    val generation: Long,
    val publisher: SdkPublisher<T>,
) {
    val completion = CompletableDeferred<AttemptCompletion>()
    private val cancellationStarted = AtomicBoolean()
    private val cancellationResult = CompletableDeferred<Throwable?>()
    val cancellationRequested = AtomicBoolean()
    val jobReady = CompletableDeferred<Job>()

    @Suppress("TooGenericExceptionCaught")
    suspend fun cancelOnce(): Throwable? {
        if (cancellationStarted.compareAndSet(false, true)) {
            val failure = withContext(NonCancellable) {
                try {
                    val job = jobReady.await()
                    cancellationRequested.set(true)
                    job.cancel()
                    job.join()
                    when (val outcome = completion.await()) {
                        is AttemptCompletion.Cancelled -> outcome.cleanupFailure
                        AttemptCompletion.Succeeded,
                        is AttemptCompletion.Failed,
                        -> null
                    }
                } catch (_: CancellationException) {
                    null
                } catch (cause: Throwable) {
                    cause
                }
            }
            cancellationResult.complete(failure)
        }
        return withContext(NonCancellable) { cancellationResult.await() }
    }
}

@Suppress("TooManyFunctions")
private class StreamCoordinator<T : Any>(
    private val scope: CoroutineScope,
    private val emit: suspend (T) -> Unit,
) {
    private val mutex = Mutex()
    private val callbackLock = ReentrantLock()
    private val callbackSequence = AtomicLong()
    private val callbackCompletions = LinkedHashMap<Long, CallbackCompletion>()
    private val completedCallbackFailures = BoundedFailureAccumulator()
    private val operationFailures = BoundedFailureAccumulator()
    private var acceptingCallbacks = true
    private var generation = 0L
    private var attempt: StreamAttempt<T>? = null
    private var futureSucceeded = false
    private var terminal: StreamTerminal = StreamTerminal.Active

    fun replaceFromCallback(publisher: SdkPublisher<T>) {
        val callback = callbackLock.withReentrantLock {
            if (!acceptingCallbacks || !scope.isActive) {
                null
            } else {
                val sequence = callbackSequence.incrementAndGet()
                CallbackCompletion(sequence).also { callbackCompletions[sequence] = it }
            }
        }
        if (callback == null) {
            val rejection = BoundedFailureAccumulator()
            publisher.cancelImmediately()?.let(rejection::record)
            rejection.throwable()?.let { throw it }
            return
        }

        val callbackStarted = AtomicBoolean()
        scope.launch {
            if (!callbackStarted.compareAndSet(false, true)) return@launch
            val result = replace(callback.sequence, publisher)
            withContext(NonCancellable) {
                completeCallback(callback, result)
            }
        }.invokeOnCompletion {
            if (callbackStarted.compareAndSet(false, true)) {
                val rejection = BoundedFailureAccumulator()
                publisher.cancelImmediately()?.let(rejection::record)
                completeCallback(callback, rejection.snapshotAndClear())
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
    private suspend fun replace(sequence: Long, publisher: SdkPublisher<T>): FailureSnapshot? {
        val failures = BoundedFailureAccumulator()
        var handedOff = false
        try {
            var previous: StreamAttempt<T>? = null
            val canClaim = mutex.withLock {
                if (terminal !is StreamTerminal.Active || futureSucceeded || sequence < generation) {
                    false
                } else {
                    generation = sequence
                    previous = attempt
                    attempt = null
                    true
                }
            }
            if (canClaim) {
                previous?.cancelOnce()?.let(failures::record)

                val current = StreamAttempt(sequence, publisher)
                val canSubscribe = mutex.withLock {
                    if (terminal is StreamTerminal.Active && !futureSucceeded && generation == sequence) {
                        attempt = current
                        true
                    } else {
                        false
                    }
                }
                if (canSubscribe) {
                    handedOff = true

                    val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        current.jobReady.complete(currentCoroutineContext().job)
                        if (current.cancellationRequested.get()) {
                            current.completion.complete(AttemptCompletion.Cancelled(null))
                            return@launch
                        }
                        try {
                            publisher.asFlow()
                                .buffer(0)
                                .collect { value ->
                                    val currentGeneration = mutex.withLock {
                                        terminal is StreamTerminal.Active &&
                                            generation == sequence &&
                                            attempt === current
                                    }
                                    if (currentGeneration) emit(value)
                                }
                            current.completion.complete(AttemptCompletion.Succeeded)
                        } catch (ce: CancellationException) {
                            current.completion.complete(
                                if (current.cancellationRequested.get()) {
                                    AttemptCompletion.Cancelled(null)
                                } else {
                                    AttemptCompletion.Failed(ce)
                                },
                            )
                            throw ce
                        } catch (cause: Throwable) {
                            current.completion.complete(
                                if (current.cancellationRequested.get()) {
                                    AttemptCompletion.Cancelled(cause)
                                } else {
                                    AttemptCompletion.Failed(cause)
                                },
                            )
                        } finally {
                            if (!current.completion.isCompleted) {
                                current.completion.complete(
                                    if (current.cancellationRequested.get()) {
                                        AttemptCompletion.Cancelled(null)
                                    } else {
                                        AttemptCompletion.Succeeded
                                    },
                                )
                            }
                        }
                    }
                    if (!current.jobReady.isCompleted) current.jobReady.complete(job)
                }
            }
        } catch (cause: Throwable) {
            if (cause !is CancellationException) failures.record(cause)
        } finally {
            if (!handedOff) publisher.cancelImmediately()?.let(failures::record)
        }
        return failures.snapshotAndClear()
    }

    fun handlerFailureFromCallback(@Suppress("UNUSED_PARAMETER") cause: Throwable) = Unit

    fun handlerCompletedFromCallback() = Unit

    suspend fun futureSucceeded() {
        val callbacks = closeCallbacks()
        drainCallbacks(callbacks)
        val current = mutex.withLock {
            if (terminal !is StreamTerminal.Active) return
            futureSucceeded = true
            attempt
        }
        var completion: AttemptCompletion? = null
        try {
            completion = current?.completion?.await()
        } finally {
            when (val outcome = completion) {
                is AttemptCompletion.Failed -> selectOperationPrimary(outcome.cause)
                is AttemptCompletion.Cancelled -> recordOperationFailure(outcome.cleanupFailure)
                AttemptCompletion.Succeeded,
                null,
                -> Unit
            }
        }
        mutex.withLock {
            if (terminal is StreamTerminal.Active && futureSucceeded) {
                terminal = StreamTerminal.Completed
            }
        }
    }

    suspend fun futureFailed(cause: Throwable) {
        withContext(NonCancellable) {
            selectOperationPrimary(cause)
            val callbacks = closeCallbacks()
            val shouldDrain = mutex.withLock {
                if (terminal !is StreamTerminal.Active) {
                    false
                } else {
                    terminal = StreamTerminal.Failed(cause)
                    true
                }
            }
            if (shouldDrain) {
                cancelActiveAttempt()
                drainCallbacks(callbacks)
            }
        }
    }

    suspend fun cancel(cause: CancellationException) {
        withContext(NonCancellable) {
            selectOperationPrimary(cause)
            val callbacks = closeCallbacks()
            val shouldDrain = mutex.withLock {
                if (terminal !is StreamTerminal.Active) {
                    false
                } else {
                    terminal = StreamTerminal.Cancelled
                    true
                }
            }
            if (shouldDrain) {
                cancelActiveAttempt()
                drainCallbacks(callbacks)
            }
        }
    }

    suspend fun cancelActiveAttempt() {
        withContext(NonCancellable) {
            val current = mutex.withLock { attempt.also { attempt = null } }
            current?.cancelOnce()?.let(::recordOperationFailure)
        }
    }

    fun materializeOperationFailure(): Throwable? =
        callbackLock.withReentrantLock { operationFailures.throwable() }

    private fun completeCallback(callback: CallbackCompletion, failure: FailureSnapshot?) {
        val shouldSignal = callbackLock.withReentrantLock {
            if (callback.logicallyCompleted) {
                false
            } else {
                callback.logicallyCompleted = true
                if (callback.drainClaimed) {
                    true
                } else {
                    callbackCompletions.remove(callback.sequence)
                    failure?.let(completedCallbackFailures::merge)
                    true
                }
            }
        }
        if (shouldSignal) callback.result.complete(failure)
    }

    private fun closeCallbacks(): CallbackDrain = callbackLock.withReentrantLock {
        acceptingCallbacks = false
        val pending = callbackCompletions.values.toList()
        pending.forEach { it.drainClaimed = true }
        callbackCompletions.clear()
        CallbackDrain(pending, completedCallbackFailures.snapshotAndClear())
    }

    private suspend fun drainCallbacks(drain: CallbackDrain) {
        withContext(NonCancellable) {
            mergeOperationFailures(drain.completedFailure)
            drain.pending.forEach { callback ->
                callback.result.await()?.let(::mergeOperationFailures)
            }
        }
    }

    private fun recordOperationFailure(failure: Throwable?) {
        callbackLock.withReentrantLock { operationFailures.record(failure) }
    }

    private fun selectOperationPrimary(failure: Throwable?) {
        callbackLock.withReentrantLock { operationFailures.selectPrimary(failure) }
    }

    private fun mergeOperationFailures(snapshot: FailureSnapshot) {
        callbackLock.withReentrantLock { operationFailures.merge(snapshot) }
    }
}

/**
 * Bedrock 네이티브 `ConverseStream` 요청 하나를 콜드 [Flow]로 스트리밍합니다.
 *
 * 수집할 때마다 과금될 수 있는 새 SDK 호출을 시작합니다. 수요는 `request(1)`로 제한하며,
 * 수집기 취소는 활성 구독과 작업 Future를 모두 취소합니다. 클라이언트 수명은 호출자가 관리합니다.
 * SDK 재시도로 의미상 같은 이벤트가 반복될 수 있습니다. 이 도우미는 정확히 한 번 전달,
 * 중복 제거, 재시도 또는 재생을 제공하지 않습니다.
 */
fun BedrockRuntimeAsyncClient.converseStreamFlow(
    request: ConverseStreamRequest,
): Flow<ConverseStreamOutput> = channelFlow {
    val coordinator = StreamCoordinator<ConverseStreamOutput>(this) { send(it) }
    val handler = object : ConverseStreamResponseHandler {
        override fun responseReceived(response: ConverseStreamResponse) = Unit

        override fun onEventStream(publisher: SdkPublisher<ConverseStreamOutput>) {
            coordinator.replaceFromCallback(publisher)
        }

        override fun exceptionOccurred(throwable: Throwable) {
            coordinator.handlerFailureFromCallback(throwable)
        }

        override fun complete() {
            coordinator.handlerCompletedFromCallback()
        }
    }

    var operation: CompletableFuture<Void>? = null
    var terminalFailure: Throwable? = null
    try {
        operation = converseStream(request, handler)
        operation.await()
        coordinator.futureSucceeded()
    } catch (ce: CancellationException) {
        coordinator.cancel(ce)
    } catch (cause: Throwable) {
        coordinator.futureFailed(cause)
    } finally {
        coordinator.cancelActiveAttempt()
        terminalFailure = coordinator.materializeOperationFailure()
    }
    terminalFailure?.let { failure -> throw failure }
}.buffer(0)

/**
 * 모델에 종속되지 않는 Bedrock 네이티브 `ConverseStream` 요청을 구성하고 스트리밍합니다.
 *
 * 반환된 [Flow]는 콜드 스트림이므로 수집할 때마다 과금될 수 있는 새 호출을 시작합니다.
 * `request(1)` 수요를 사용하고 취소를 전달하며, 클라이언트 종료는 호출자가 담당합니다.
 * SDK 재시도로 의미상 중복이 생길 수 있으며 정확히 한 번 전달, 중복 제거, 재시도 또는 재생을 추가하지 않습니다.
 */
inline fun BedrockRuntimeAsyncClient.converseStreamFlow(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseStreamRequest.Builder.() -> Unit = {},
): Flow<ConverseStreamOutput> =
    converseStreamFlow(converseStreamRequestOf(modelId, messages, inferenceConfig, builder))

/**
 * 빈 텍스트를 보존하면서 원본 순서대로 텍스트 델타를 선택합니다.
 *
 * 텍스트가 아닌 이벤트는 걸러냅니다. 이 매핑은 로깅, 병렬 변환, 재시도 또는 재생을 추가하지 않습니다.
 */
fun Flow<ConverseStreamOutput>.textDeltaFlow(): Flow<String> =
    map(ConverseStreamOutput::textDeltaOrNull).castNotNull<String>()

@Suppress("TooGenericExceptionCaught")
private fun <T : Any> SdkPublisher<T>.cancelImmediately(): Throwable? =
    try {
        subscribe(
            object : Subscriber<T> {
                override fun onSubscribe(subscription: Subscription) {
                    subscription.cancel()
                }

                override fun onNext(item: T) = Unit
                override fun onError(throwable: Throwable) = Unit
                override fun onComplete() = Unit
            },
        )
        null
    } catch (failure: Throwable) {
        failure
    }
