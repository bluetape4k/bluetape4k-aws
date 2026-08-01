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
            if (!canClaim) return

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
            if (!canSubscribe) return
            handedOff = true

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
        } finally {
            if (!handedOff) publisher.cancelImmediately()
        }
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
