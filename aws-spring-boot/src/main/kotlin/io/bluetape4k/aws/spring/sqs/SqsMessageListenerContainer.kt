package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 하나의 `@SqsListener` 엔드포인트를 실행하는 SQS 메시지 리스너 컨테이너.
 */
@Suppress(
    "TooManyFunctions",
    "TooGenericExceptionCaught",
    "ThrowsCount",
)
class SqsMessageListenerContainer internal constructor(
    private val endpoint: SqsListenerEndpoint,
    private val operations: SqsOperations,
    private val invoker: SqsListenerMethodInvoker,
    private val interceptors: List<SqsListenerInterceptor>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
): SmartLifecycle {

    companion object : KLogging()

    private class ListenerGeneration(
        val id: Long,
        val scope: CoroutineScope,
        val pollerJobs: CopyOnWriteArrayList<Job> = CopyOnWriteArrayList(),
        val handlerJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet(),
    )

    private enum class LifecycleState {
        STOPPED,
        RUNNING,
        STOPPING_RECEIVE,
        DRAINING,
    }

    private val generation = AtomicReference<ListenerGeneration?>()
    private val lifecycleState = AtomicReference(LifecycleState.STOPPED)
    private val generationSequence = AtomicLong()
    private val lifecycleLock = Any()
    private var resolvedQueueUrl: String? = null

    override fun start() {
        if (!endpoint.autoStartup) {
            return
        }

        val current: ListenerGeneration
        synchronized(lifecycleLock) {
            when (lifecycleState.get()) {
                LifecycleState.STOPPING_RECEIVE,
                LifecycleState.DRAINING,
                -> throw IllegalStateException("listener is stopping")
                LifecycleState.RUNNING -> return
                LifecycleState.STOPPED -> {
                    current = ListenerGeneration(
                        id = generationSequence.incrementAndGet(),
                        scope = CoroutineScope(SupervisorJob() + dispatcher),
                    )
                    generation.set(current)
                    lifecycleState.set(LifecycleState.RUNNING)
                }
            }
        }

        repeat(endpoint.concurrency) { pollerId ->
            current.pollerJobs += current.scope.launch {
                pollLoop(current, pollerId)
            }
        }
    }

    override fun stop() {
        stop(Runnable {})
    }

    override fun stop(callback: Runnable) {
        val current: ListenerGeneration
        synchronized(lifecycleLock) {
            if (!lifecycleState.compareAndSet(LifecycleState.RUNNING, LifecycleState.STOPPING_RECEIVE)) {
                callback.run()
                return
            }
            current = requireNotNull(generation.getAndSet(null))
        }

        CoroutineScope(dispatcher).launch {
            try {
                current.pollerJobs.forEach { it.cancel() }
                current.pollerJobs.joinAll()

                lifecycleState.set(LifecycleState.DRAINING)
                val drained = withTimeoutOrNull(endpoint.stopTimeoutMillis) {
                    while (current.handlerJobs.isNotEmpty()) {
                        current.handlerJobs.toList().joinAll()
                    }
                } != null
                if (!drained) {
                    current.handlerJobs.toList().forEach { it.cancel() }
                }
                current.scope.cancel()
            } finally {
                synchronized(lifecycleLock) {
                    if (generation.get() === current) {
                        generation.set(null)
                    }
                    lifecycleState.set(LifecycleState.STOPPED)
                }
                callback.run()
            }
        }
    }

    override fun isRunning(): Boolean = lifecycleState.get() == LifecycleState.RUNNING

    override fun isAutoStartup(): Boolean = endpoint.autoStartup

    override fun getPhase(): Int = endpoint.phase

    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun pollLoop(current: ListenerGeneration, pollerId: Int) {
        var batchSequence = 0L
        var receiveAttempt = 1

        while (generation.get() === current) {
            val queueUrl = resolveQueueUrlForPoll(current, receiveAttempt)
            if (queueUrl == null) {
                receiveAttempt++
                continue
            }
            val correlation = SqsListenerBatchCorrelation(
                generation = current.id,
                pollerId = pollerId,
                batchSequence = ++batchSequence,
            )
            val messages = receiveMessagesForPoll(current, queueUrl, correlation, receiveAttempt)
            if (messages == null) {
                receiveAttempt++
                continue
            }
            receiveAttempt = 1
            if (generation.get() !== current) {
                return
            }
            dispatchMessages(current, queueUrl, messages, correlation)
        }
    }

    private suspend fun resolveQueueUrlForPoll(
        current: ListenerGeneration,
        receiveAttempt: Int,
    ): String? = try {
        resolveQueueUrl()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Error) {
        failGeneration(current)
        throw e
    } catch (e: Throwable) {
        log.warn("SQS queue URL resolution failed: listenerId=${endpoint.id}, queue=${endpoint.queue}", e)
        delay(endpoint.retry.nextDelay(receiveAttempt))
        null
    }

    private suspend fun receiveMessagesForPoll(
        current: ListenerGeneration,
        queueUrl: String,
        correlation: SqsListenerBatchCorrelation,
        receiveAttempt: Int,
    ): List<SqsReceivedMessage>? = try {
        interceptors.forEach { it.beforeReceive(endpoint.id, queueUrl, correlation) }
        val received = operations.receive(
            queueUrl = queueUrl,
            maxMessages = endpoint.maxMessages,
            waitTimeSeconds = endpoint.waitTimeSeconds,
            visibilityTimeoutSeconds = endpoint.visibilityTimeoutSeconds,
        )
        interceptors.forEach { it.afterReceive(endpoint.id, queueUrl, received, null, correlation) }
        received
    } catch (e: CancellationException) {
        throw e
    } catch (e: Error) {
        interceptors.forEach { it.afterReceive(endpoint.id, queueUrl, emptyList(), e, correlation) }
        failGeneration(current)
        throw e
    } catch (e: Throwable) {
        interceptors.forEach { it.afterReceive(endpoint.id, queueUrl, emptyList(), e, correlation) }
        log.warn("SQS receive failed: listenerId=${endpoint.id}, queueUrl=$queueUrl", e)
        delay(endpoint.retry.nextDelay(receiveAttempt))
        null
    }

    private suspend fun dispatchMessages(
        current: ListenerGeneration,
        queueUrl: String,
        messages: List<SqsReceivedMessage>,
        correlation: SqsListenerBatchCorrelation,
    ) {
        try {
            if (endpoint.batch) {
                launchHandler(current) {
                    handleBatch(queueUrl, messages, correlation, current)
                }
            } else {
                messages.forEach { message ->
                    if (generation.get() !== current) {
                        return
                    }
                    launchHandler(current) {
                        handle(queueUrl, message, current)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Error) {
            failGeneration(current)
            throw e
        }
    }

    private suspend fun launchHandler(
        current: ListenerGeneration,
        block: suspend () -> Unit,
    ) {
        val handlerJob = current.scope.launch(start = CoroutineStart.LAZY) { block() }
        current.handlerJobs += handlerJob
        handlerJob.invokeOnCompletion { current.handlerJobs -= handlerJob }
        handlerJob.start()
        handlerJob.join()
    }

    private suspend fun handle(
        queueUrl: String,
        message: SqsReceivedMessage,
        generation: ListenerGeneration? = null,
    ) {
        var attempt = 1

        while (attempt <= endpoint.retry.maxAttempts) {
            val context = SqsListenerInvocationContext(endpoint.id, queueUrl, message, attempt)
            val acknowledgement = DefaultSqsAcknowledgement(
                context = context,
                operations = operations,
                interceptors = interceptors,
                operationGuard = { generation?.ensureActiveOperation() },
            )
            try {
                interceptors.forEach { it.beforeHandle(context) }
                invoker.invoke(message, acknowledgement)
                interceptors.forEach { it.afterHandle(context, null) }
                if (!invoker.manualAcknowledgement) {
                    acknowledgement.acknowledge()
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Error) {
                generation?.let(::failGeneration)
                throw e
            } catch (e: Throwable) {
                interceptors.forEach { it.afterHandle(context, e) }
                if (acknowledgement.completed) {
                    return
                }
                if (attempt >= endpoint.retry.maxAttempts) {
                    handleFailure(queueUrl, message, acknowledgement, e)
                    return
                }
                delay(endpoint.retry.nextDelay(attempt))
                attempt++
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun handleBatch(
        queueUrl: String,
        messages: List<SqsReceivedMessage>,
        correlation: SqsListenerBatchCorrelation,
        generation: ListenerGeneration,
    ) {
        if (messages.isEmpty()) {
            return
        }
        val acknowledgement = DefaultSqsBatchAcknowledgement(
            listenerId = endpoint.id,
            queueUrl = queueUrl,
            messages = messages,
            operations = operations,
            interceptors = interceptors,
            attempt = 1,
            correlation = correlation,
            operationGuard = { generation.ensureActiveOperation() },
        )
        val manual = endpoint.acknowledgementMode == SqsAcknowledgementMode.MANUAL
        val context = SqsListenerInvocationContext(endpoint.id, queueUrl, messages.first(), 1)
        var attempt = 1

        while (attempt <= endpoint.retry.maxAttempts) {
            acknowledgement.updateAttempt(attempt)
            val pending = acknowledgement.pending
            if (pending.isEmpty()) {
                return
            }
            val attemptContext = context.copy(attempt = attempt)
            try {
                invokeBatchHandler(pending, acknowledgement, manual, attemptContext, correlation)
                if (manual) {
                    return
                }

                val result = acknowledgement.acknowledge(pending)
                if (result.status == SqsBatchAcknowledgementStatus.SUCCESS || acknowledgement.completed) {
                    return
                }
                if (attempt >= endpoint.retry.maxAttempts) {
                    handleBatchFailure(queueUrl, acknowledgement)
                    return
                }
                interceptors.forEach { it.onBatchRetry(attemptContext, correlation, pending.size, attempt + 1, null) }
            } catch (e: CancellationException) {
                interceptors.forEach { it.onBatchCancellation(attemptContext, correlation, pending.size) }
                throw e
            } catch (e: Error) {
                failGeneration(generation)
                throw e
            } catch (e: Throwable) {
                if (acknowledgement.completed) {
                    return
                }
                if (attempt >= endpoint.retry.maxAttempts) {
                    handleBatchFailure(queueUrl, acknowledgement)
                    return
                }
                interceptors.forEach { it.onBatchRetry(attemptContext, correlation, pending.size, attempt + 1, e) }
            }
            delay(endpoint.retry.nextDelay(attempt))
            attempt++
        }
    }

    private suspend fun invokeBatchHandler(
        pending: List<SqsReceivedMessage>,
        acknowledgement: DefaultSqsBatchAcknowledgement,
        manual: Boolean,
        context: SqsListenerInvocationContext,
        correlation: SqsListenerBatchCorrelation,
    ) {
        interceptors.forEach { it.beforeBatchHandle(context, correlation, pending.size) }
        var handlerFailure: Throwable? = null
        try {
            invoker.invokeBatch(pending, acknowledgement.takeIf { manual })
        } catch (e: Throwable) {
            handlerFailure = e
            throw e
        } finally {
            interceptors.forEach { it.afterBatchHandle(context, handlerFailure, correlation, pending.size) }
        }
    }

    private suspend fun handleBatchFailure(
        queueUrl: String,
        acknowledgement: DefaultSqsBatchAcknowledgement,
    ) {
        val pending = acknowledgement.pending
        if (pending.isEmpty()) {
            return
        }
        endpoint.errorVisibilityTimeoutSeconds?.let { timeoutSeconds ->
            try {
                acknowledgement.changeVisibility(pending, timeoutSeconds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Error) {
                throw e
            } catch (e: Throwable) {
                log.warn(
                    "SQS batch changeVisibility failed: listenerId=${endpoint.id}, queueUrl=$queueUrl, " +
                        "batchSize=${pending.size}",
                    e,
                )
            }
        }
    }

    private suspend fun handleFailure(
        queueUrl: String,
        message: SqsReceivedMessage,
        acknowledgement: SqsAcknowledgement,
        error: Throwable,
    ) {
        log.warn(
            "SQS message handling failed: listenerId=${endpoint.id}, queueUrl=$queueUrl, messageId=${message.messageId}",
            error,
        )
        endpoint.errorVisibilityTimeoutSeconds?.let {
            try {
                if (!acknowledgement.completed) {
                    acknowledgement.changeVisibility(it)
                }
            } catch (ve: CancellationException) {
                throw ve
            } catch (ve: Error) {
                throw ve
            } catch (ve: Throwable) {
                log.warn(
                    "SQS changeVisibility failed: listenerId=${endpoint.id}, queueUrl=$queueUrl, messageId=${message.messageId}",
                    ve,
                )
            }
        }
    }

    private suspend fun resolveQueueUrl(): String {
        resolvedQueueUrl?.let { return it }

        val queueUrl = when {
            endpoint.queue.startsWith("http://") || endpoint.queue.startsWith("https://") -> endpoint.queue
            else -> operations.getQueueUrl(endpoint.queue)
        }
        resolvedQueueUrl = queueUrl
        return queueUrl
    }

    private fun ListenerGeneration.ensureActiveOperation() {
        if (generation.get() !== this || lifecycleState.get() != LifecycleState.RUNNING) {
            throw CancellationException("SQS listener generation is stopping")
        }
    }

    private fun failGeneration(current: ListenerGeneration) {
        synchronized(lifecycleLock) {
            if (generation.get() !== current) {
                return
            }
            generation.set(null)
            lifecycleState.set(LifecycleState.STOPPED)
            current.scope.cancel()
        }
    }
}

private fun SqsProperties.Retry.nextDelay(failedAttempt: Int): Long {
    if (initialBackoff.isZero) {
        return 0L
    }
    val exponential = initialBackoff.toMillis() * Math.pow(multiplier, (failedAttempt - 1).toDouble())
    val capped = maxBackoff?.let { minOf(exponential, it.toMillis().toDouble()) } ?: exponential
    val jittered = if (jitterRatio == 0.0) {
        capped
    } else {
        val delta = capped * jitterRatio
        ThreadLocalRandom.current().nextDouble(capped - delta, capped + delta)
    }
    return Duration.ofMillis(jittered.toLong().coerceAtLeast(0L)).toMillis()
}
