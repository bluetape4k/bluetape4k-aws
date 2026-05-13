package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
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
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
import kotlin.math.roundToLong
import kotlin.reflect.KClass

private const val MIN_MESSAGE_COUNT = 1
private const val MAX_MESSAGE_COUNT = 10
private const val MIN_WAIT_TIME_SECONDS = 0
private const val MAX_WAIT_TIME_SECONDS = 20
private const val MAX_VISIBILITY_SECONDS = 43_200
private const val MAX_MESSAGE_ATTRIBUTES = 10

/**
 * Backoff policy for receive-loop failures.
 *
 * Contract:
 * - [initialDelay] and [maxDelay] must be positive.
 * - [multiplier] must be at least 1.0.
 */
data class SqsPollBackoff(
    val initialDelay: Duration = Duration.ofMillis(250),
    val maxDelay: Duration = Duration.ofSeconds(5),
    val multiplier: Double = 2.0,
) {
    init {
        require(!initialDelay.isZero && !initialDelay.isNegative) { "initialDelay must be positive." }
        require(!maxDelay.isZero && !maxDelay.isNegative) { "maxDelay must be positive." }
        require(maxDelay >= initialDelay) { "maxDelay must be greater than or equal to initialDelay." }
        require(multiplier >= 1.0) { "multiplier must be at least 1.0." }
    }

    internal fun newState(): BackoffState = BackoffState(this)
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
 * Runtime configuration for [SqsConsumerRuntime].
 *
 * Most applications should create this through [SqsConsumerPluginConfig]. Direct
 * construction is useful for tests and non-Ktor bootstrapping.
 */
data class SqsConsumerRuntimeConfig(
    val sqsAsyncClient: SqsAsyncClient,
    val queueUrl: String? = null,
    val queueName: String? = null,
    val coroutines: Int = 1,
    val maxMessages: Int = 10,
    val waitTimeSeconds: Int = 20,
    val visibilityTimeoutSeconds: Int? = null,
    val deleteOnSuccess: Boolean = true,
    val failureVisibilityTimeoutSeconds: Int? = null,
    val deadLetterQueueUrl: String? = null,
    val deadLetterQueueName: String? = null,
    val shutdownTimeout: Duration = Duration.ofSeconds(30),
    val pollBackoff: SqsPollBackoff = SqsPollBackoff(),
    val visibilityHeartbeatSeconds: Int? = null,
    val dispatcher: CoroutineDispatcher? = null,
    val converter: SqsMessageConverter = StringOrByteArraySqsMessageConverter,
    val messageType: KClass<out Any>,
    val messageHandler: suspend SqsMessageContext.(Any) -> Unit,
) {
    init {
        validateQueue(queueUrl, queueName, "queueUrl", "queueName")
        require(coroutines >= 1) { "coroutines must be at least 1." }
        require(maxMessages in MIN_MESSAGE_COUNT..MAX_MESSAGE_COUNT) {
            "maxMessages must be between $MIN_MESSAGE_COUNT and $MAX_MESSAGE_COUNT."
        }
        require(waitTimeSeconds in MIN_WAIT_TIME_SECONDS..MAX_WAIT_TIME_SECONDS) {
            "waitTimeSeconds must be between $MIN_WAIT_TIME_SECONDS and $MAX_WAIT_TIME_SECONDS."
        }
        visibilityTimeoutSeconds?.let {
            require(it in 1..MAX_VISIBILITY_SECONDS) { "visibilityTimeoutSeconds must be between 1 and $MAX_VISIBILITY_SECONDS." }
        }
        failureVisibilityTimeoutSeconds?.let {
            require(it in 0..MAX_VISIBILITY_SECONDS) {
                "failureVisibilityTimeoutSeconds must be between 0 and $MAX_VISIBILITY_SECONDS."
            }
        }
        validateDeadLetterQueue()
        require(!shutdownTimeout.isZero && !shutdownTimeout.isNegative) { "shutdownTimeout must be positive." }
        visibilityHeartbeatSeconds?.let { heartbeat ->
            val visibility = requireNotNull(visibilityTimeoutSeconds) {
                "visibilityHeartbeatSeconds requires visibilityTimeoutSeconds."
            }
            require(heartbeat in 1 until visibility) {
                "visibilityHeartbeatSeconds must be positive and lower than visibilityTimeoutSeconds."
            }
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
}

/**
 * Per-message context passed to SQS handlers.
 *
 * Contract:
 * - Helper methods call AWS using the same [SqsConsumerRuntime].
 * - Calling [delete] marks the message as deleted so `deleteOnSuccess` will not
 *   delete it a second time.
 */
class SqsMessageContext internal constructor(
    private val runtime: SqsConsumerRuntime,
    val queueUrl: String,
    val message: Message,
) {
    internal var deleted: Boolean = false

    /** Deletes the current message from the source queue. */
    suspend fun delete() {
        runtime.delete(queueUrl, message.receiptHandle())
        deleted = true
    }

    /** Changes the visibility timeout for the current message. */
    suspend fun changeVisibility(timeoutSeconds: Int) {
        runtime.changeVisibility(queueUrl, message.receiptHandle(), timeoutSeconds)
    }

    /** Publishes a message to the source queue or to [targetQueueUrl] when provided. */
    suspend fun send(
        messageBody: String,
        targetQueueUrl: String = queueUrl,
        delaySeconds: Int? = null,
    ): SendMessageResponse =
        runtime.send(messageBody, targetQueueUrl, delaySeconds)
}

/**
 * Coroutine-based SQS consumer runtime used by the Ktor plugin.
 *
 * Contract:
 * - [start] is idempotent and launches [SqsConsumerRuntimeConfig.coroutines] pollers.
 * - [stop] stops new receives, waits for in-flight handlers up to
 *   [SqsConsumerRuntimeConfig.shutdownTimeout], then cancels remaining handlers.
 * - [SqsAsyncClient] is injected and not closed by this runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SqsConsumerRuntime(
    private val config: SqsConsumerRuntimeConfig,
) {
    companion object: KLogging()

    private val running = AtomicBoolean(false)
    private val pollerJobs = CopyOnWriteArrayList<Job>()
    private val handlerJobs = ConcurrentHashMap.newKeySet<Job>()
    private val handlerPermitReleases = ConcurrentHashMap<Job, AtomicBoolean>()
    private val queueUrlMutex = Mutex()
    private val handlerPermits = Semaphore(config.coroutines * config.maxMessages)

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var resolvedQueueUrl: String? = config.queueUrl

    /** True while the runtime accepts receives and handler launches. */
    val isRunning: Boolean
        get() = running.get()

    /** Starts polling if the runtime is not already running. */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

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

    /** Stops pollers and drains in-flight handlers according to the shutdown contract. */
    suspend fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }

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
    }

    /** Publishes [messageBody] to the configured source queue. */
    suspend fun send(
        messageBody: String,
        delaySeconds: Int? = null,
    ): SendMessageResponse =
        send(messageBody, resolveQueueUrl(), delaySeconds)

    /** Publishes [messageBody] to [queueUrl]. */
    suspend fun send(
        messageBody: String,
        queueUrl: String,
        delaySeconds: Int? = null,
    ): SendMessageResponse =
        config.sqsAsyncClient.sendMessage {
            it.queueUrl(queueUrl)
            it.messageBody(messageBody)
            delaySeconds?.let(it::delaySeconds)
        }.await()

    internal suspend fun delete(queueUrl: String, receiptHandle: String) {
        config.sqsAsyncClient.deleteMessage {
            it.queueUrl(queueUrl)
            it.receiptHandle(receiptHandle)
        }.await()
    }

    internal suspend fun changeVisibility(queueUrl: String, receiptHandle: String, timeoutSeconds: Int) {
        require(timeoutSeconds in 0..MAX_VISIBILITY_SECONDS) {
            "timeoutSeconds must be between 0 and $MAX_VISIBILITY_SECONDS."
        }
        config.sqsAsyncClient.changeMessageVisibility {
            it.queueUrl(queueUrl)
            it.receiptHandle(receiptHandle)
            it.visibilityTimeout(timeoutSeconds)
        }.await()
    }

    private suspend fun pollLoop() {
        val backoff = config.pollBackoff.newState()

        while (running.get() && currentCoroutineContext().isActive) {
            var permits = 0
            try {
                val queueUrl = resolveQueueUrl()
                permits = acquireHandlerPermits()
                val response = config.sqsAsyncClient.receiveMessage {
                    it.queueUrl(queueUrl)
                    it.maxNumberOfMessages(permits)
                    it.waitTimeSeconds(config.waitTimeSeconds)
                    it.messageAttributeNames("All")
                    it.messageSystemAttributeNamesWithStrings("All")
                    config.visibilityTimeoutSeconds?.let(it::visibilityTimeout)
                }.await()

                backoff.reset()
                if (!running.get()) {
                    repeat(permits) { handlerPermits.release() }
                    permits = 0
                    return
                }

                val messages = response.messages().orEmpty()
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
        try {
            try {
                val payload = convert(message)
                config.messageHandler(context, payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFailure(queueUrl, message, e)
                return
            }

            if (config.deleteOnSuccess && !context.deleted) {
                try {
                    delete(queueUrl, message.receiptHandle())
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

    private suspend fun handleFailure(queueUrl: String, message: Message, cause: Exception) {
        when {
            config.hasManualDeadLetterQueue -> {
                forwardToDeadLetterQueue(queueUrl, message, cause)
                delete(queueUrl, message.receiptHandle())
            }

            config.failureVisibilityTimeoutSeconds != null -> {
                changeVisibility(queueUrl, message.receiptHandle(), config.failureVisibilityTimeoutSeconds)
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
}
