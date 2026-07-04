package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SQS message listener container that runs one `@SqsListener` endpoint.
 */
class SqsMessageListenerContainer internal constructor(
    private val endpoint: SqsListenerEndpoint,
    private val operations: SqsOperations,
    private val invoker: SqsListenerMethodInvoker,
    private val interceptors: List<SqsListenerInterceptor>,
): SmartLifecycle {

    companion object : KLogging()

    private val running = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var jobs: List<Job> = emptyList()
    private var resolvedQueueUrl: String? = null

    override fun start() {
        if (!endpoint.autoStartup || !running.compareAndSet(false, true)) {
            return
        }

        val job = SupervisorJob()
        val currentScope = CoroutineScope(job + Dispatchers.IO)
        scope = currentScope

        jobs = List(endpoint.concurrency) {
            currentScope.launch {
                pollLoop()
            }
        }
    }

    override fun stop() {
        stop(Runnable {})
    }

    override fun stop(callback: Runnable) {
        if (!running.compareAndSet(true, false)) {
            callback.run()
            return
        }

        val currentScope = scope
        if (currentScope == null) {
            callback.run()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            currentScope.cancel()
            withTimeoutOrNull(endpoint.stopTimeoutMillis) {
                jobs.joinAll()
            }
            callback.run()
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = endpoint.autoStartup

    override fun getPhase(): Int = endpoint.phase

    private suspend fun pollLoop() {
        val queueUrl = resolveQueueUrl()

        while (running.get()) {
            try {
                interceptors.forEach { it.beforeReceive(endpoint.id, queueUrl) }
                val messages = operations.receive(
                    queueUrl = queueUrl,
                    maxMessages = endpoint.maxMessages,
                    waitTimeSeconds = endpoint.waitTimeSeconds,
                    visibilityTimeoutSeconds = endpoint.visibilityTimeoutSeconds,
                )
                interceptors.forEach { it.afterReceive(endpoint.id, queueUrl, messages, null) }
                if (!running.get()) {
                    return
                }
                messages.forEach { handle(queueUrl, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                interceptors.forEach { it.afterReceive(endpoint.id, queueUrl, emptyList(), e) }
                log.warn("SQS receive failed: listenerId=${endpoint.id}, queueUrl=$queueUrl", e)
                // Keep the listener alive; individual message failures are handled in handle().
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
