package io.bluetape4k.aws.spring.sqs

/**
 * Intercepts SQS listener receive, handler, and acknowledgement phases.
 *
 * Implement this interface to add metrics, tracing, structured logging, or
 * policy checks without coupling the listener container to a specific
 * observability library.
 */
interface SqsListenerInterceptor {

    suspend fun beforeReceive(listenerId: String, queueUrl: String) {
    }

    suspend fun afterReceive(listenerId: String, queueUrl: String, messages: List<SqsReceivedMessage>, error: Throwable?) {
    }

    suspend fun beforeHandle(context: SqsListenerInvocationContext) {
    }

    suspend fun afterHandle(context: SqsListenerInvocationContext, error: Throwable?) {
    }

    suspend fun beforeAcknowledgement(context: SqsListenerInvocationContext, action: SqsAcknowledgementAction) {
    }

    suspend fun afterAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        error: Throwable?,
    ) {
    }
}

/**
 * Context for one SQS listener handler invocation.
 */
data class SqsListenerInvocationContext(
    val listenerId: String,
    val queueUrl: String,
    val message: SqsReceivedMessage,
    val attempt: Int,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = -5671325956177467194L
    }
}

/**
 * Acknowledgement operation being performed for an SQS message.
 */
enum class SqsAcknowledgementAction {
    ACK,
    NACK,
    CHANGE_VISIBILITY,
}

