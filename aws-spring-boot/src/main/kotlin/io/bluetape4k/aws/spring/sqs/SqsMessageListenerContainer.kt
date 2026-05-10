package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.context.SmartLifecycle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 하나의 `@SqsListener` 엔드포인트를 실행하는 SQS 메시지 리스너 컨테이너.
 */
class SqsMessageListenerContainer internal constructor(
    private val endpoint: SqsListenerEndpoint,
    private val operations: SqsOperations,
    private val invoker: SqsListenerMethodInvoker,
): SmartLifecycle {

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
                val messages = operations.receive(
                    queueUrl = queueUrl,
                    maxMessages = endpoint.maxMessages,
                    waitTimeSeconds = endpoint.waitTimeSeconds,
                    visibilityTimeoutSeconds = endpoint.visibilityTimeoutSeconds,
                )
                if (!running.get()) {
                    return
                }
                messages.forEach { handle(queueUrl, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Keep the listener alive; individual message failures are handled in handle().
            }
        }
    }

    private suspend fun handle(queueUrl: String, message: SqsReceivedMessage) {
        try {
            invoker.invoke(message)
            operations.delete(queueUrl, message.receiptHandle)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            endpoint.errorVisibilityTimeoutSeconds?.let {
                operations.changeVisibility(queueUrl, message.receiptHandle, it)
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
