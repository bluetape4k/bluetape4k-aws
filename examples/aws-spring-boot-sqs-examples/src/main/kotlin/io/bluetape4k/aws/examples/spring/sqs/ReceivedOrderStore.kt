package io.bluetape4k.aws.examples.spring.sqs

import io.bluetape4k.aws.spring.sqs.SqsAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgementAction
import io.bluetape4k.aws.spring.sqs.SqsListener
import io.bluetape4k.aws.spring.sqs.SqsListenerInterceptor
import io.bluetape4k.aws.spring.sqs.SqsListenerInvocationContext
import org.springframework.stereotype.Component
import java.io.Serializable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

@Component
class ReceivedOrderStore {
    private val messages = CopyOnWriteArrayList<String>()
    private val typedOrders = CopyOnWriteArrayList<OrderPayload>()
    private val attempts = ConcurrentHashMap<String, Int>()
    private val listenerEvents = CopyOnWriteArrayList<ListenerEvent>()

    fun record(message: String) {
        messages += message
    }

    fun record(order: OrderPayload) {
        typedOrders += order
    }

    fun recordAttempt(key: String): Int =
        attempts.merge(key, 1, Int::plus) ?: 1

    fun recordEvent(event: ListenerEvent) {
        listenerEvents += event
    }

    fun recent(): List<String> =
        messages.toList()

    fun recentTyped(): List<OrderPayload> =
        typedOrders.toList()

    fun attemptCount(key: String): Int =
        attempts[key] ?: 0

    fun events(): List<ListenerEvent> =
        listenerEvents.toList()

    fun clear() {
        messages.clear()
        typedOrders.clear()
        attempts.clear()
        listenerEvents.clear()
    }
}

/**
 * JSON payload consumed by the typed SQS listener example.
 */
class OrderPayload: Serializable {
    var id: String = ""
    var amount: Long = 0

    companion object {
        private const val serialVersionUID: Long = -7547002303655513015L
    }
}

/**
 * Listener lifecycle event captured through [SqsListenerInterceptor].
 */
data class ListenerEvent(
    val listenerId: String,
    val action: String,
    val attempt: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 6240484093828939919L
    }
}

@Component
class OrderMessageListener(
    private val store: ReceivedOrderStore,
) {

    @SqsListener(queue = "\${example.aws.sqs.listener-queue:orders}", maxMessages = 1, waitTimeSeconds = 1)
    fun handle(message: String) {
        store.record(message)
    }

    @SqsListener(
        id = "typed-order-listener",
        queue = "\${example.aws.sqs.typed-listener-queue:typed-orders}",
        maxMessages = 1,
        waitTimeSeconds = 1,
    )
    suspend fun handleTyped(order: OrderPayload, acknowledgement: SqsAcknowledgement) {
        store.record(order)
        acknowledgement.acknowledge()
    }

    @SqsListener(
        id = "retrying-order-listener",
        queue = "\${example.aws.sqs.retry-listener-queue:retry-orders}",
        maxMessages = 1,
        waitTimeSeconds = 1,
    )
    suspend fun handleWithRetry(message: String, acknowledgement: SqsAcknowledgement) {
        val attempt = store.recordAttempt(message)
        if (attempt < 2) {
            throw IllegalStateException("Retryable example failure for $message")
        }
        store.record("retried:$message")
        acknowledgement.acknowledge()
    }
}

@Component
class RecordingSqsListenerInterceptor(
    private val store: ReceivedOrderStore,
): SqsListenerInterceptor {

    override suspend fun afterHandle(context: SqsListenerInvocationContext, error: Throwable?) {
        store.recordEvent(
            ListenerEvent(
                listenerId = context.listenerId,
                action = if (error == null) "handled" else "failed",
                attempt = context.attempt,
            )
        )
    }

    override suspend fun afterAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        error: Throwable?,
    ) {
        store.recordEvent(
            ListenerEvent(
                listenerId = context.listenerId,
                action = action.name.lowercase(),
                attempt = context.attempt,
            )
        )
    }
}
