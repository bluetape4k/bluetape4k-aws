package io.bluetape4k.aws.bedrock

import io.bluetape4k.aws.bedrock.model.converseStreamRequestOf
import io.bluetape4k.aws.bedrock.model.textDeltaOrNull
import io.bluetape4k.coroutines.flow.extensions.castNotNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private sealed interface StreamTerminal {
    data object Active : StreamTerminal
    data object Completed : StreamTerminal
    data class Failed(val cause: Throwable) : StreamTerminal
    data object Cancelled : StreamTerminal
}

private class StreamAttempt<T : Any>(
    val generation: Long,
    val publisher: SdkPublisher<T>,
) {
    val completion = CompletableDeferred<Result<Unit>>()
    val cancelled = AtomicBoolean()
    val jobReady = CompletableDeferred<Job>()

    fun cancelOnce() {
        if (!cancelled.compareAndSet(false, true)) return
        if (jobReady.isCompleted) {
            jobReady.getCompleted().cancel()
        } else {
            publisher.cancelImmediately()
        }
    }
}

private class StreamCoordinator<T : Any>(
    private val scope: CoroutineScope,
    private val emit: suspend (T) -> Unit,
) {
    private val mutex = Mutex()
    private val callbackLock = Any()
    private val callbackSequence = AtomicLong()
    private val callbackCompletions = mutableListOf<CompletableDeferred<Unit>>()
    private val handlerFailures = mutableMapOf<Long, Throwable>()
    private var acceptingCallbacks = true
    private var generation = 0L
    private var attempt: StreamAttempt<T>? = null
    private var futureSucceeded = false
    private var terminal: StreamTerminal = StreamTerminal.Active

    fun replaceFromCallback(publisher: SdkPublisher<T>) {
        var sequence = 0L
        val callbackCompleted = CompletableDeferred<Unit>()
        val accepted = synchronized(callbackLock) {
            if (!acceptingCallbacks || !scope.isActive) {
                false
            } else {
                sequence = callbackSequence.incrementAndGet()
                callbackCompletions += callbackCompleted
                true
            }
        }
        if (!accepted) {
            publisher.cancelImmediately()
            return
        }
        val callbackStarted = AtomicBoolean()
        scope.launch {
            callbackStarted.set(true)
            try {
                replace(sequence, publisher)
            } finally {
                callbackCompleted.complete(Unit)
            }
        }.invokeOnCompletion {
            if (!callbackStarted.get()) {
                publisher.cancelImmediately()
                callbackCompleted.complete(Unit)
            }
        }
    }

    private suspend fun replace(sequence: Long, publisher: SdkPublisher<T>) {
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
        if (!canClaim) {
            publisher.cancelImmediately()
            return
        }

        previous?.cancelOnce()
        previous?.jobReady?.await()?.join()

        val current = StreamAttempt(sequence, publisher)
        val canSubscribe = mutex.withLock {
            if (terminal is StreamTerminal.Active && !futureSucceeded && generation == sequence) {
                attempt = current
                true
            } else {
                false
            }
        }
        if (!canSubscribe) {
            publisher.cancelImmediately()
            return
        }

        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            current.jobReady.complete(currentCoroutineContext().job)
            if (current.cancelled.get()) return@launch
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
                current.completion.complete(Result.success(Unit))
            } catch (ce: CancellationException) {
                current.completion.complete(Result.failure(ce))
                throw ce
            } catch (cause: Throwable) {
                current.completion.complete(Result.failure(cause))
            }
        }
        if (!current.jobReady.isCompleted) current.jobReady.complete(job)
    }

    fun handlerFailureFromCallback(cause: Throwable) {
        synchronized(callbackLock) {
            if (acceptingCallbacks && scope.isActive) {
                handlerFailures[callbackSequence.get()] = cause
            }
        }
    }

    fun handlerCompletedFromCallback() = Unit

    suspend fun futureSucceeded() {
        val callbacks = closeCallbacks()
        callbacks.forEach { it.await() }
        val current = mutex.withLock {
            if (terminal !is StreamTerminal.Active) return
            futureSucceeded = true
            attempt
        }
        val result = current?.completion?.await()
        val failure = mutex.withLock {
            if (terminal !is StreamTerminal.Active) return
            terminal = StreamTerminal.Completed
            if (attempt === current) {
                result?.exceptionOrNull()
                    ?: synchronized(callbackLock) {
                        handlerFailures[current?.generation ?: generation]
                    }
            } else {
                null
            }
        }
        failure?.let { throw it }
    }

    suspend fun futureFailed(cause: Throwable) {
        closeCallbacks()
        val current = mutex.withLock {
            if (terminal !is StreamTerminal.Active) return
            terminal = StreamTerminal.Failed(cause)
            attempt.also { attempt = null }
        }
        current?.cancelOnce()
    }

    suspend fun cancel() {
        closeCallbacks()
        val current = mutex.withLock {
            if (terminal !is StreamTerminal.Active) return
            terminal = StreamTerminal.Cancelled
            attempt.also { attempt = null }
        }
        current?.cancelOnce()
    }

    suspend fun cancelActiveAttempt() {
        val current = mutex.withLock { attempt.also { attempt = null } }
        current?.cancelOnce()
    }

    private fun closeCallbacks(): List<CompletableDeferred<Unit>> =
        synchronized(callbackLock) {
            acceptingCallbacks = false
            callbackCompletions.toList()
        }
}

/**
 * Streams one native Bedrock `ConverseStream` request as a cold [Flow].
 *
 * Each collection starts a new potentially billable SDK call. Demand is
 * limited to `request(1)`, and collector cancellation cancels both the active
 * subscription and operation future. The caller owns the client lifetime.
 * SDK retries may repeat semantically equivalent events; this helper provides
 * neither exactly-once delivery nor deduplication, retry, or replay.
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
    try {
        operation = converseStream(request, handler)
        operation.await()
        coordinator.futureSucceeded()
    } catch (ce: CancellationException) {
        coordinator.cancel()
        throw ce
    } catch (cause: Throwable) {
        coordinator.futureFailed(cause)
        throw cause
    } finally {
        coordinator.cancelActiveAttempt()
    }
}.buffer(0)

/**
 * Builds and streams a model-neutral native Bedrock `ConverseStream` request.
 *
 * The returned [Flow] is cold, so every collection starts a new potentially
 * billable call. It uses `request(1)` demand and forwards cancellation while
 * leaving client closure to the caller. SDK retries may produce semantic
 * duplicates; no exactly-once, deduplication, retry, or replay is added.
 */
inline fun BedrockRuntimeAsyncClient.converseStreamFlow(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseStreamRequest.Builder.() -> Unit = {},
): Flow<ConverseStreamOutput> =
    converseStreamFlow(converseStreamRequestOf(modelId, messages, inferenceConfig, builder))

/**
 * Selects text deltas in source order while preserving empty text.
 *
 * Non-text events are filtered. This mapping adds no logging, parallel
 * transformation, retry, or replay.
 */
fun Flow<ConverseStreamOutput>.textDeltaFlow(): Flow<String> =
    map(ConverseStreamOutput::textDeltaOrNull).castNotNull<String>()

private fun <T : Any> SdkPublisher<T>.cancelImmediately() {
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
}
