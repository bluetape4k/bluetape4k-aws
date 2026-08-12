package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong
import kotlin.reflect.KClass

private const val MIN_MESSAGE_COUNT = 1
private const val MAX_MESSAGE_COUNT = 10
private const val MIN_WAIT_TIME_SECONDS = 0
private const val MAX_WAIT_TIME_SECONDS = 20
private const val MAX_VISIBILITY_SECONDS = 43_200
private const val MAX_MESSAGE_ATTRIBUTES = 10

internal object KtorSqsObservationOperations {
    const val SEND: String = "send"
    const val RECEIVE: String = "receive"
    const val INVOKE: String = "invoke"
    const val ACK: String = "ack"
    const val NACK: String = "nack"
    const val CONVERT: String = "convert"
}

internal object KtorSqsObservationOutcomes {
    const val SUCCESS: String = "success"
    const val FAILURE: String = "failure"
}

internal object KtorSqsObservationTags {
    const val EXCEPTION: String = "exception"
    const val MESSAGE_COUNT: String = "message_count"
    const val RETRY_DELAY_MS: String = "retry_delay_ms"
    const val VISIBILITY_TIMEOUT: String = "visibility_timeout"
}

/**
 * receive loop 실패에 적용할 backoff 정책입니다.
 *
 * 계약:
 * - [initialDelay]와 [maxDelay]는 양수여야 합니다.
 * - [multiplier]는 `1.0` 이상이어야 합니다.
 */
data class SqsPollBackoff(
    /** 첫 receive 실패 후 기다릴 초기 지연 시간입니다. */
    val initialDelay: Duration = Duration.ofMillis(250),
    /** backoff가 증가할 때 허용되는 최대 지연 시간입니다. */
    val maxDelay: Duration = Duration.ofSeconds(5),
    /** 다음 지연 시간을 계산할 때 이전 지연 시간에 곱할 배율입니다. */
    val multiplier: Double = 2.0,
): Serializable {
    init {
        initialDelay.toNanos().requirePositiveNumber("initialDelay")
        maxDelay.toNanos().requirePositiveNumber("maxDelay")
        maxDelay.toNanos().requireGe(initialDelay.toNanos(), "maxDelay")
        multiplier.requireGe(1.0, "multiplier")
    }

    internal fun newState(): BackoffState = BackoffState(this)

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal class BackoffState(
    private val policy: SqsPollBackoff,
) {
    private var nextDelay: Duration = policy.initialDelay

    fun reset() {
        nextDelay = policy.initialDelay
    }

    fun next(): Duration {
        val current = nextDelay
        val multipliedMillis = (nextDelay.toMillis() * policy.multiplier).roundToLong()
        nextDelay = Duration.ofMillis(multipliedMillis.coerceAtMost(policy.maxDelay.toMillis()))
        return current
    }
}

/**
 * [SqsConsumerRuntime] 실행 설정입니다.
 *
 * 대부분의 application은 [SqsConsumerPluginConfig]로 이 설정을 생성합니다. 직접 생성은 테스트 또는
 * Ktor를 거치지 않는 bootstrap 코드에서 유용합니다.
 */
data class SqsConsumerRuntimeConfig(
    /** receive, delete, visibility 변경, send 요청에 사용할 AWS SDK v2 async SQS client입니다. */
    val sqsAsyncClient: SqsAsyncClient,
    /** runtime이 [sqsAsyncClient]를 소유하며 [stop] 후 닫아야 하는지 나타냅니다. */
    val ownsClient: Boolean = false,
    /** 소비할 queue URL입니다. [queueName]과 동시에 설정할 수 없습니다. */
    val queueUrl: String? = null,
    /** runtime 시작 후 SQS에서 URL로 확인할 queue name입니다. [queueUrl]과 동시에 설정할 수 없습니다. */
    val queueName: String? = null,
    /** 병렬 receive loop를 실행할 poller coroutine 수입니다. */
    val coroutines: Int = 1,
    /** receive 요청당 가져올 최대 메시지 수입니다. AWS SQS 범위는 `1..10`입니다. */
    val maxMessages: Int = 10,
    /** SQS long polling 대기 시간 초 단위 값입니다. AWS SQS 범위는 `0..20`입니다. */
    val waitTimeSeconds: Int = 20,
    /** receive 요청에 지정할 선택적 visibility timeout 초 단위 값입니다. */
    val visibilityTimeoutSeconds: Int? = null,
    /** handler가 정상 완료된 메시지를 자동 삭제할지 나타냅니다. */
    val deleteOnSuccess: Boolean = true,
    /** 실패한 메시지에 적용할 고정 visibility timeout입니다. [failureVisibilityStrategy]와 동시에 사용할 수 없습니다. */
    val failureVisibilityTimeoutSeconds: Int? = null,
    /** 실패 메시지를 직접 전달할 dead-letter queue URL입니다. native SQS redrive를 사용할 수 없을 때만 사용합니다. */
    val deadLetterQueueUrl: String? = null,
    /** URL로 확인할 dead-letter queue name입니다. [deadLetterQueueUrl]과 동시에 설정할 수 없습니다. */
    val deadLetterQueueName: String? = null,
    /** shutdown 시 처리 중인 handler가 끝나기를 기다릴 최대 시간입니다. */
    val shutdownTimeout: Duration = Duration.ofSeconds(30),
    /** receive loop 실패 후 재시도 전 사용할 backoff 정책입니다. */
    val pollBackoff: SqsPollBackoff = SqsPollBackoff(),
    /** handler 실행 중 메시지 visibility를 주기적으로 연장할 heartbeat 간격 초 단위 값입니다. */
    val visibilityHeartbeatSeconds: Int? = null,
    /** poller와 handler에 사용할 coroutine dispatcher입니다. `null`이면 IO dispatcher를 제한해 사용합니다. */
    val dispatcher: CoroutineDispatcher? = null,
    /** AWS SQS [Message]를 handler payload로 변환할 converter입니다. */
    val converter: SqsMessageConverter = StringOrByteArraySqsMessageConverter,
    /** converter가 handler 호출 전에 실패했을 때 적용할 정책입니다. */
    val conversionFailurePolicy: SqsConversionFailurePolicy = SqsConversionFailurePolicy.HandleAsFailure,
    /** 실패 context에 따라 visibility timeout을 계산하는 전략입니다. [failureVisibilityTimeoutSeconds]와 동시에 사용할 수 없습니다. */
    val failureVisibilityStrategy: SqsFailureVisibilityStrategy? = null,
    /** receive, invoke, ack, nack lifecycle에 호출할 interceptor 목록입니다. */
    val interceptors: List<SqsConsumerInterceptor> = emptyList(),
    /** runtime operation 관측 이벤트를 받을 observer 목록입니다. */
    val observers: List<SqsConsumerObserver> = emptyList(),
    /** handler가 받을 payload 타입입니다. */
    val messageType: KClass<out Any>,
    /** 변환된 payload를 처리할 suspend message handler입니다. */
    val messageHandler: suspend SqsMessageContext.(Any) -> Unit,
): Serializable {
    init {
        validateQueue(queueUrl, queueName, "queueUrl", "queueName")
        coroutines.requirePositiveNumber("coroutines")
        maxMessages.requireInRange(MIN_MESSAGE_COUNT, MAX_MESSAGE_COUNT, "maxMessages")
        waitTimeSeconds.requireInRange(MIN_WAIT_TIME_SECONDS, MAX_WAIT_TIME_SECONDS, "waitTimeSeconds")
        visibilityTimeoutSeconds?.let {
            it.requireInRange(1, MAX_VISIBILITY_SECONDS, "visibilityTimeoutSeconds")
        }
        failureVisibilityTimeoutSeconds?.let {
            it.requireInRange(0, MAX_VISIBILITY_SECONDS, "failureVisibilityTimeoutSeconds")
        }
        validateDeadLetterQueue()
        shutdownTimeout.toNanos().requirePositiveNumber("shutdownTimeout")
        visibilityHeartbeatSeconds?.let { heartbeat ->
            val visibility = requireNotNull(visibilityTimeoutSeconds) {
                "visibilityHeartbeatSeconds requires visibilityTimeoutSeconds."
            }
            heartbeat.requireInRange(1, visibility - 1, "visibilityHeartbeatSeconds")
        }
        require(failureVisibilityTimeoutSeconds == null || failureVisibilityStrategy == null) {
            "failureVisibilityTimeoutSeconds and failureVisibilityStrategy are mutually exclusive."
        }
    }

    internal val hasManualDeadLetterQueue: Boolean =
        !deadLetterQueueUrl.isNullOrBlank() || !deadLetterQueueName.isNullOrBlank()

    private fun validateDeadLetterQueue() {
        if (deadLetterQueueUrl.isNullOrBlank() && deadLetterQueueName.isNullOrBlank()) {
            return
        }
        validateQueue(deadLetterQueueUrl, deadLetterQueueName, "deadLetterQueueUrl", "deadLetterQueueName")
        require(failureVisibilityTimeoutSeconds == null) {
            "Manual dead-letter forwarding and failureVisibilityTimeoutSeconds are mutually exclusive."
        }
        require(failureVisibilityStrategy == null) {
            "Manual dead-letter forwarding and failureVisibilityStrategy are mutually exclusive."
        }
    }

    private fun validateQueue(
        url: String?,
        name: String?,
        urlLabel: String,
        nameLabel: String,
    ) {
        require(url.isNullOrBlank() xor name.isNullOrBlank()) {
            "Exactly one of $urlLabel and $nameLabel must be configured."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SQS handler에 전달되는 메시지별 context입니다.
 *
 * 계약:
 * - helper method는 동일한 [SqsConsumerRuntime]을 통해 AWS를 호출합니다.
 * - [delete]를 호출하면 메시지를 삭제됨으로 표시하므로 `deleteOnSuccess`가 같은 메시지를 다시 삭제하지 않습니다.
 */
class SqsMessageContext internal constructor(
    private val runtime: SqsConsumerRuntime,
    /** 현재 메시지를 받은 source queue URL입니다. */
    val queueUrl: String,
    /** handler가 처리 중인 AWS SDK SQS 메시지 원본입니다. */
    val message: Message,
) {
    @Volatile
    internal var deleted: Boolean = false

    /** 현재 메시지를 source queue에서 삭제합니다. */
    suspend fun delete() {
        ack()
    }

    /** 현재 메시지를 source queue에서 삭제해 acknowledge 처리합니다. */
    suspend fun ack() {
        runtime.ack(this)
    }

    /** 현재 메시지의 visibility timeout을 변경합니다. */
    suspend fun changeVisibility(timeoutSeconds: Int) {
        runtime.changeVisibility(queueUrl, message.receiptHandle(), timeoutSeconds)
    }

    /** 현재 메시지의 visibility timeout을 변경해 negative acknowledge 처리합니다. */
    suspend fun nack(timeoutSeconds: Int = 0) {
        runtime.nack(this, timeoutSeconds)
    }

    /** source queue 또는 지정한 [targetQueueUrl]로 메시지를 발행합니다. */
    suspend fun send(
        messageBody: String,
        targetQueueUrl: String = queueUrl,
        delaySeconds: Int? = null,
    ): SendMessageResponse =
        runtime.send(messageBody, targetQueueUrl, delaySeconds)
}

/**
 * Ktor plugin이 사용하는 coroutine 기반 SQS consumer runtime입니다.
 *
 * 계약:
 * - [start]는 처음 한 번만 [SqsConsumerRuntimeConfig.coroutines]개 poller를 시작합니다. 이미 실행 중이거나
 *   종료 중인 호출은 idempotent하게 무시하고, [stop] 이후 호출은 `IllegalStateException`으로 실패합니다.
 * - [stop]은 새 receive를 중단하고 [SqsConsumerRuntimeConfig.shutdownTimeout]까지 처리 중인 handler를 기다린 뒤
 *   남은 handler를 취소합니다. 시작 전 호출도 runtime을 영구적으로 종료합니다.
 * - [SqsAsyncClient]는 plugin이 생성한 경우에만 닫습니다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SqsConsumerRuntime(
    private val config: SqsConsumerRuntimeConfig,
) {
    companion object: KLogging()

    private enum class LifecycleState {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
    }

    private val lifecycleState = AtomicReference(LifecycleState.NEW)
    private val lifecycleLock = Any()
    private val pollerJobs = CopyOnWriteArrayList<Job>()
    private val handlerJobs = ConcurrentHashMap.newKeySet<Job>()
    private val handlerPermitReleases = ConcurrentHashMap<Job, AtomicBoolean>()
    private val ownedClientClosed = AtomicBoolean(false)
    private val queueUrlMutex = Mutex()
    private val handlerPermits = Semaphore(config.coroutines * config.maxMessages)

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var resolvedQueueUrl: String? = config.queueUrl

    /** runtime이 receive와 handler launch를 받을 수 있는 상태이면 `true`입니다. */
    val isRunning: Boolean
        get() = lifecycleState.get() == LifecycleState.RUNNING

    /** runtime을 시작합니다. 종료된 runtime은 다시 시작할 수 없습니다. */
    fun start() {
        synchronized(lifecycleLock) {
            when (lifecycleState.get()) {
                LifecycleState.NEW -> {
                    lifecycleState.set(LifecycleState.RUNNING)
                    val dispatcher = config.dispatcher ?: Dispatchers.IO.limitedParallelism(config.coroutines)
                    val currentScope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("sqs-consumer"))
                    scope = currentScope
                    pollerJobs.clear()
                    repeat(config.coroutines) { index ->
                        pollerJobs += currentScope.launch(CoroutineName("sqs-poller-$index")) {
                            pollLoop()
                        }
                    }
                }

                LifecycleState.RUNNING,
                LifecycleState.STOPPING,
                -> return

                LifecycleState.STOPPED ->
                    throw IllegalStateException("SqsConsumerRuntime cannot be started after it has stopped.")
            }
        }
    }

    /** shutdown 계약에 따라 poller를 중지하고 처리 중인 handler를 drain합니다. */
    suspend fun stop() {
        val closeBeforeStart = synchronized(lifecycleLock) {
            when (lifecycleState.get()) {
                LifecycleState.NEW -> {
                    lifecycleState.set(LifecycleState.STOPPED)
                    true
                }

                LifecycleState.RUNNING -> {
                    lifecycleState.set(LifecycleState.STOPPING)
                    false
                }

                LifecycleState.STOPPING,
                LifecycleState.STOPPED,
                -> null
            }
        }

        if (closeBeforeStart == null) {
            return
        }
        if (closeBeforeStart) {
            closeOwnedClient()
            return
        }

        try {
            val currentScope = scope
            val currentPollers = pollerJobs.toList()
            currentPollers.forEach { it.cancel() }
            currentPollers.joinAll()

            val timeoutMillis = config.shutdownTimeout.toMillis()
            val drained = withTimeoutOrNull(timeoutMillis) {
                while (handlerJobs.isNotEmpty()) {
                    handlerJobs.toList().joinAll()
                }
            } != null

            if (!drained) {
                handlerJobs.toList().forEach { job ->
                    job.cancel()
                    releaseHandlerPermit(job)
                }
            }
            currentScope?.cancel()
            pollerJobs.clear()
            if (!drained) {
                handlerJobs.clear()
                handlerPermitReleases.clear()
            }
            scope = null
        } finally {
            try {
                closeOwnedClient()
            } finally {
                lifecycleState.set(LifecycleState.STOPPED)
            }
        }
    }

    private suspend fun closeOwnedClient() {
        if (!config.ownsClient || !ownedClientClosed.compareAndSet(false, true)) {
            return
        }

        runInterruptible(Dispatchers.IO) {
            config.sqsAsyncClient.close()
        }
    }

    /** [messageBody]를 설정된 source queue로 발행합니다. */
    suspend fun send(
        messageBody: String,
        delaySeconds: Int? = null,
    ): SendMessageResponse =
        send(messageBody, resolveQueueUrl(), delaySeconds)

    /** [messageBody]를 [queueUrl]로 발행합니다. */
    suspend fun send(
        messageBody: String,
        queueUrl: String,
        delaySeconds: Int? = null,
    ): SendMessageResponse {
        val startedAt = System.nanoTime()
        return try {
            val response = config.sqsAsyncClient.sendMessage {
                it.queueUrl(queueUrl)
                it.messageBody(messageBody)
                delaySeconds?.let(it::delaySeconds)
            }.await()
            observe(KtorSqsObservationOperations.SEND, KtorSqsObservationOutcomes.SUCCESS, queueUrl, startedAt)
            response
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            observeFailure(KtorSqsObservationOperations.SEND, queueUrl, startedAt, e)
            throw e
        }
    }

    internal suspend fun delete(queueUrl: String, receiptHandle: String) {
        config.sqsAsyncClient.deleteMessage {
            it.queueUrl(queueUrl)
            it.receiptHandle(receiptHandle)
        }.await()
    }

    internal suspend fun ack(context: SqsMessageContext) {
        if (context.deleted) {
            return
        }
        config.interceptors.forEach { it.beforeAck(context) }
        val startedAt = System.nanoTime()
        try {
            delete(context.queueUrl, context.message.receiptHandle())
            context.deleted = true
            config.interceptors.forEach { it.afterAck(context) }
            observe(KtorSqsObservationOperations.ACK, KtorSqsObservationOutcomes.SUCCESS, context, startedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            observeFailure(KtorSqsObservationOperations.ACK, context, startedAt, e)
            throw e
        }
    }

    internal suspend fun changeVisibility(queueUrl: String, receiptHandle: String, timeoutSeconds: Int) {
        timeoutSeconds.requireInRange(0, MAX_VISIBILITY_SECONDS, "timeoutSeconds")
        config.sqsAsyncClient.changeMessageVisibility {
            it.queueUrl(queueUrl)
            it.receiptHandle(receiptHandle)
            it.visibilityTimeout(timeoutSeconds)
        }.await()
    }

    internal suspend fun nack(context: SqsMessageContext, timeoutSeconds: Int) {
        config.interceptors.forEach { it.beforeNack(context, timeoutSeconds) }
        val startedAt = System.nanoTime()
        try {
            changeVisibility(context.queueUrl, context.message.receiptHandle(), timeoutSeconds)
            config.interceptors.forEach { it.afterNack(context, timeoutSeconds) }
            observe(
                KtorSqsObservationOperations.NACK,
                KtorSqsObservationOutcomes.SUCCESS,
                context,
                startedAt,
                mapOf(KtorSqsObservationTags.VISIBILITY_TIMEOUT to timeoutSeconds.toString()),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            observe(
                operation = KtorSqsObservationOperations.NACK,
                outcome = KtorSqsObservationOutcomes.FAILURE,
                context = context,
                startedAt = startedAt,
                tags = mapOf(
                    KtorSqsObservationTags.VISIBILITY_TIMEOUT to timeoutSeconds.toString(),
                    KtorSqsObservationTags.EXCEPTION to e::class.qualifiedName.orEmpty(),
                ),
            )
            throw e
        }
    }

    private suspend fun pollLoop() {
        val backoff = config.pollBackoff.newState()

        while (isRunning && currentCoroutineContext().isActive) {
            var permits = 0
            var queueUrl: String? = null
            try {
                queueUrl = resolveQueueUrl()
                permits = acquireHandlerPermits()
                config.interceptors.forEach { it.beforeReceive(queueUrl) }
                val startedAt = System.nanoTime()
                val response = config.sqsAsyncClient.receiveMessage {
                    it.queueUrl(queueUrl)
                    it.maxNumberOfMessages(permits)
                    it.waitTimeSeconds(config.waitTimeSeconds)
                    it.messageAttributeNames("All")
                    it.messageSystemAttributeNamesWithStrings("All")
                    config.visibilityTimeoutSeconds?.let(it::visibilityTimeout)
                }.await()

                backoff.reset()
                if (!isRunning) {
                    repeat(permits) { handlerPermits.release() }
                    permits = 0
                    return
                }

                val messages = response.messages().orEmpty()
                config.interceptors.forEach { it.afterReceive(queueUrl, messages) }
                observe(
                    operation = KtorSqsObservationOperations.RECEIVE,
                    outcome = KtorSqsObservationOutcomes.SUCCESS,
                    queueUrl = queueUrl,
                    startedAt = startedAt,
                    tags = mapOf(KtorSqsObservationTags.MESSAGE_COUNT to messages.size.toString()),
                )
                repeat(permits - messages.size) { handlerPermits.release() }
                messages.forEach { message ->
                    launchHandler(queueUrl, message)
                }
                permits = 0
            } catch (e: CancellationException) {
                repeat(permits) { handlerPermits.release() }
                throw e
            } catch (e: Exception) {
                repeat(permits) { handlerPermits.release() }
                val retryDelay = backoff.next()
                config.interceptors.forEach { it.receiveFailed(queueUrl, e, retryDelay) }
                observe(
                    operation = KtorSqsObservationOperations.RECEIVE,
                    outcome = KtorSqsObservationOutcomes.FAILURE,
                    queueUrl = queueUrl,
                    tags = mapOf(
                        KtorSqsObservationTags.EXCEPTION to e::class.qualifiedName.orEmpty(),
                        KtorSqsObservationTags.RETRY_DELAY_MS to retryDelay.toMillis().toString(),
                    ),
                )
                log.warn(e) {
                    "SQS receive loop failed. Retrying after ${retryDelay.toMillis()} ms."
                }
                delay(retryDelay.toMillis().coerceAtLeast(1L))
            }
        }
    }

    private suspend fun acquireHandlerPermits(): Int {
        var acquired = 0
        try {
            handlerPermits.acquire()
            acquired++
            while (acquired < config.maxMessages && handlerPermits.tryAcquire()) {
                acquired++
            }
            return acquired
        } catch (e: CancellationException) {
            repeat(acquired) { handlerPermits.release() }
            throw e
        }
    }

    private fun launchHandler(queueUrl: String, message: Message) {
        val currentScope = scope
        if (currentScope == null) {
            handlerPermits.release()
            return
        }
        val permitReleased = AtomicBoolean(false)
        fun releasePermit() {
            if (permitReleased.compareAndSet(false, true)) {
                handlerPermits.release()
            }
        }

        val job = currentScope.launch {
            try {
                handleMessage(queueUrl, message)
            } finally {
                releasePermit()
            }
        }
        handlerPermitReleases[job] = permitReleased
        handlerJobs += job
        job.invokeOnCompletion {
            handlerJobs -= job
            handlerPermitReleases -= job
        }
    }

    private fun releaseHandlerPermit(job: Job) {
        handlerPermitReleases[job]?.let { released ->
            if (released.compareAndSet(false, true)) {
                handlerPermits.release()
            }
        }
    }

    private suspend fun handleMessage(queueUrl: String, message: Message) {
        val context = SqsMessageContext(this, queueUrl, message)
        val heartbeat = startVisibilityHeartbeat(context)
        val startedAt = System.nanoTime()
        try {
            val payload = try {
                convert(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleConversionFailure(queueUrl, message, context, e)
                return
            }

            try {
                config.interceptors.forEach { it.beforeInvoke(context) }
                config.messageHandler(context, payload)
                currentCoroutineContext().ensureActive()
                config.interceptors.forEach { it.afterInvoke(context) }
                observe(KtorSqsObservationOperations.INVOKE, KtorSqsObservationOutcomes.SUCCESS, context, startedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                config.interceptors.forEach { it.invokeFailed(context, e) }
                observeFailure(KtorSqsObservationOperations.INVOKE, context, startedAt, e)
                handleFailure(queueUrl, message, e, SqsConsumerFailurePhase.Handler)
                return
            }

            if (config.deleteOnSuccess && !context.deleted) {
                try {
                    ack(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) {
                        "Failed to delete successfully handled SQS message. Message will remain eligible for redelivery."
                    }
                }
            }
        } finally {
            heartbeat?.cancelAndJoin()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun convert(message: Message): Any =
        config.converter.convert(message, config.messageType as KClass<Any>)

    private fun startVisibilityHeartbeat(context: SqsMessageContext): Job? {
        val heartbeatSeconds = config.visibilityHeartbeatSeconds ?: return null
        val visibilitySeconds = config.visibilityTimeoutSeconds ?: return null
        val currentScope = scope ?: return null

        return currentScope.launch(CoroutineName("sqs-visibility-heartbeat")) {
            while (currentCoroutineContext().isActive) {
                try {
                    delay(Duration.ofSeconds(heartbeatSeconds.toLong()).toMillis())
                    if (context.deleted) {
                        return@launch
                    }
                    context.changeVisibility(visibilitySeconds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) {
                        "Failed to extend SQS message visibility."
                    }
                }
            }
        }
    }

    private suspend fun handleConversionFailure(
        queueUrl: String,
        message: Message,
        context: SqsMessageContext,
        cause: Exception,
    ) {
        observeFailure(KtorSqsObservationOperations.CONVERT, context, cause = cause)
        when (config.conversionFailurePolicy) {
            SqsConversionFailurePolicy.HandleAsFailure ->
                handleFailure(queueUrl, message, cause, SqsConsumerFailurePhase.Conversion)

            SqsConversionFailurePolicy.Delete ->
                ack(context)

            SqsConversionFailurePolicy.Ignore -> Unit
        }
    }

    private suspend fun handleFailure(
        queueUrl: String,
        message: Message,
        cause: Exception,
        phase: SqsConsumerFailurePhase,
    ) {
        when {
            config.hasManualDeadLetterQueue -> {
                forwardToDeadLetterQueue(queueUrl, message, cause)
                delete(queueUrl, message.receiptHandle())
            }

            else -> {
                val failureContext = SqsConsumerFailureContext(queueUrl, message, cause, phase)
                val timeoutSeconds = config.failureVisibilityStrategy?.visibilityTimeoutSeconds(failureContext)
                    ?: config.failureVisibilityTimeoutSeconds
                timeoutSeconds?.let {
                    changeVisibility(queueUrl, message.receiptHandle(), it)
                }
            }
        }
    }

    private suspend fun forwardToDeadLetterQueue(queueUrl: String, message: Message, cause: Throwable) {
        val targetQueueUrl = resolveDeadLetterQueueUrl()
        val attributes = deadLetterAttributes(queueUrl, message, cause)

        config.sqsAsyncClient.sendMessage {
            it.queueUrl(targetQueueUrl)
            it.messageBody(message.body().orEmpty())
            it.messageAttributes(attributes)
        }.await()
    }

    private fun deadLetterAttributes(
        queueUrl: String,
        message: Message,
        cause: Throwable,
    ): Map<String, MessageAttributeValue> {
        val attributes = LinkedHashMap<String, MessageAttributeValue>()
        attributes["bluetape4k-original-message-id"] = stringAttribute(message.messageId().orEmpty())
        attributes["bluetape4k-original-queue-url"] = stringAttribute(queueUrl)
        attributes["bluetape4k-original-receive-count"] =
            stringAttribute(message.attributesAsStrings()["ApproximateReceiveCount"].orEmpty())
        attributes["bluetape4k-error-class"] = stringAttribute(cause::class.qualifiedName.orEmpty())
        attributes["bluetape4k-error-message"] = stringAttribute(cause.message.orEmpty().take(256))

        message.messageAttributes().orEmpty()
            .filterKeys { it !in attributes }
            .entries
            .take(MAX_MESSAGE_ATTRIBUTES - attributes.size)
            .forEach { (name, value) -> attributes[name] = value }

        return attributes
    }

    private suspend fun resolveQueueUrl(): String {
        resolvedQueueUrl?.let { return it }
        return queueUrlMutex.withLock {
            resolvedQueueUrl ?: config.sqsAsyncClient.getQueueUrl {
                it.queueName(config.queueName)
            }.await().queueUrl().also {
                resolvedQueueUrl = it
            }
        }
    }

    private suspend fun resolveDeadLetterQueueUrl(): String {
        config.deadLetterQueueUrl?.takeIf { it.isNotBlank() }?.let { return it }
        return config.sqsAsyncClient.getQueueUrl {
            it.queueName(config.deadLetterQueueName)
        }.await().queueUrl()
    }

    private fun stringAttribute(value: String): MessageAttributeValue =
        MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(value)
            .build()

    private fun observe(
        operation: String,
        outcome: String,
        context: SqsMessageContext,
        startedAt: Long? = null,
        tags: Map<String, String> = emptyMap(),
    ) {
        observe(operation, outcome, context.queueUrl, context.message.messageId(), startedAt, tags)
    }

    private fun observe(
        operation: String,
        outcome: String,
        queueUrl: String? = null,
        startedAt: Long? = null,
        tags: Map<String, String> = emptyMap(),
    ) {
        observe(operation, outcome, queueUrl, null, startedAt, tags)
    }

    private fun observe(
        operation: String,
        outcome: String,
        queueUrl: String?,
        messageId: String?,
        startedAt: Long?,
        tags: Map<String, String>,
    ) {
        if (config.observers.isEmpty()) {
            return
        }

        val duration = startedAt?.let { Duration.ofNanos(System.nanoTime() - it) }
        val observation = SqsConsumerObservation(
            operation = operation,
            outcome = outcome,
            queueUrl = queueUrl,
            messageId = messageId,
            duration = duration,
            tags = tags,
        )
        config.observers.forEach { observer ->
            runCatching { observer.observe(observation) }
                .onFailure { e ->
                    log.warn(e) { "SQS consumer observer failed." }
                }
        }
    }

    private fun observeFailure(
        operation: String,
        context: SqsMessageContext,
        startedAt: Long? = null,
        cause: Throwable,
    ) {
        observe(
            operation = operation,
            outcome = KtorSqsObservationOutcomes.FAILURE,
            context = context,
            startedAt = startedAt,
            tags = mapOf(KtorSqsObservationTags.EXCEPTION to cause::class.qualifiedName.orEmpty()),
        )
    }

    private fun observeFailure(
        operation: String,
        queueUrl: String?,
        startedAt: Long? = null,
        cause: Throwable,
    ) {
        observe(
            operation = operation,
            outcome = KtorSqsObservationOutcomes.FAILURE,
            queueUrl = queueUrl,
            startedAt = startedAt,
            tags = mapOf(KtorSqsObservationTags.EXCEPTION to cause::class.qualifiedName.orEmpty()),
        )
    }
}
