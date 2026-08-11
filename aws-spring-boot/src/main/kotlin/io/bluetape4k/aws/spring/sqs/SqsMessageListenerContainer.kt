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
import java.util.concurrent.atomic.AtomicReference

/**
 * 하나의 `@SqsListener` 엔드포인트를 실행하는 SQS 메시지 리스너 컨테이너.
 */
class SqsMessageListenerContainer internal constructor(
    private val endpoint: SqsListenerEndpoint,
    private val operations: SqsOperations,
    private val invoker: SqsListenerMethodInvoker,
    private val interceptors: List<SqsListenerInterceptor>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
): SmartLifecycle {

    companion object : KLogging()

    private class ListenerGeneration(
        val scope: CoroutineScope,
        val pollerJobs: CopyOnWriteArrayList<Job> = CopyOnWriteArrayList(),
        val handlerJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet(),
    )

    private val generation = AtomicReference<ListenerGeneration?>()
    private var resolvedQueueUrl: String? = null

    override fun start() {
        if (!endpoint.autoStartup) {
            return
        }

        val current = ListenerGeneration(CoroutineScope(SupervisorJob() + dispatcher))
        if (!generation.compareAndSet(null, current)) {
            current.scope.cancel()
            return
        }

        repeat(endpoint.concurrency) {
            current.pollerJobs += current.scope.launch {
                pollLoop(current)
            }
        }
    }

    override fun stop() {
        stop(Runnable {})
    }

    override fun stop(callback: Runnable) {
        val current = generation.getAndSet(null)
        if (current == null) {
            callback.run()
            return
        }

        CoroutineScope(dispatcher).launch {
            try {
                current.pollerJobs.forEach { it.cancel() }
                current.pollerJobs.joinAll()

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
                callback.run()
            }
        }
    }

    override fun isRunning(): Boolean = generation.get() != null

    override fun isAutoStartup(): Boolean = endpoint.autoStartup

    override fun getPhase(): Int = endpoint.phase

    private suspend fun pollLoop(current: ListenerGeneration) {
        val queueUrl = resolveQueueUrl()

        while (generation.get() === current) {
            try {
                interceptors.forEach { it.beforeReceive(endpoint.id, queueUrl) }
                val messages = operations.receive(
                    queueUrl = queueUrl,
                    maxMessages = endpoint.maxMessages,
                    waitTimeSeconds = endpoint.waitTimeSeconds,
                    visibilityTimeoutSeconds = endpoint.visibilityTimeoutSeconds,
                )
                interceptors.forEach { it.afterReceive(endpoint.id, queueUrl, messages, null) }
                if (generation.get() !== current) {
                    return
                }
                messages.forEach { message ->
                    if (generation.get() !== current) {
                        return
                    }
                    val handlerJob = current.scope.launch(start = CoroutineStart.LAZY) {
                        handle(queueUrl, message)
                    }
                    current.handlerJobs += handlerJob
                    handlerJob.invokeOnCompletion { current.handlerJobs -= handlerJob }
                    handlerJob.start()
                    handlerJob.join()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                interceptors.forEach { it.afterReceive(endpoint.id, queueUrl, emptyList(), e) }
                log.warn("SQS receive failed: listenerId=${endpoint.id}, queueUrl=$queueUrl", e)
                // listener는 계속 실행하고 개별 message 실패는 handle()에서 처리한다.
            }
        }
    }

    private suspend fun handle(queueUrl: String, message: SqsReceivedMessage) {
        var attempt = 1

        while (attempt <= endpoint.retry.maxAttempts) {
            val context = SqsListenerInvocationContext(endpoint.id, queueUrl, message, attempt)
            val acknowledgement = DefaultSqsAcknowledgement(
                context = context,
                operations = operations,
                interceptors = interceptors,
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
