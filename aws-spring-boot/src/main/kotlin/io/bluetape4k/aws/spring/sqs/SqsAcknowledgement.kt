package io.bluetape4k.aws.spring.sqs

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manual acknowledgement handle for an `@SqsListener` invocation.
 *
 * ## Behavior / Contract
 *
 * Listener methods that declare this parameter opt out of automatic message
 * deletion. Call [acknowledge] after successful processing, or [nack] to make
 * the message visible again after the supplied timeout.
 */
interface SqsAcknowledgement {

    /**
     * Whether this acknowledgement has already completed with ack or nack.
     */
    val completed: Boolean

    /**
     * Deletes the message from the queue.
     */
    suspend fun acknowledge()

    /**
     * Changes visibility and marks the message as negatively acknowledged.
     */
    suspend fun nack(timeoutSeconds: Int = 0)

    /**
     * Changes message visibility without completing acknowledgement state.
     */
    suspend fun changeVisibility(timeoutSeconds: Int)
}

internal class DefaultSqsAcknowledgement(
    private val context: SqsListenerInvocationContext,
    private val operations: SqsOperations,
    private val interceptors: List<SqsListenerInterceptor>,
) : SqsAcknowledgement {

    private val terminal = AtomicBoolean(false)

    override val completed: Boolean
        get() = terminal.get()

    override suspend fun acknowledge() {
        if (!terminal.compareAndSet(false, true)) {
            return
        }
        runAcknowledgement(SqsAcknowledgementAction.ACK) {
            operations.delete(context.queueUrl, context.message.receiptHandle)
        }
    }

    override suspend fun nack(timeoutSeconds: Int) {
        if (!terminal.compareAndSet(false, true)) {
            return
        }
        changeVisibilityInternal(SqsAcknowledgementAction.NACK, timeoutSeconds)
    }

    override suspend fun changeVisibility(timeoutSeconds: Int) {
        changeVisibilityInternal(SqsAcknowledgementAction.CHANGE_VISIBILITY, timeoutSeconds)
    }

    private suspend fun changeVisibilityInternal(
        action: SqsAcknowledgementAction,
        timeoutSeconds: Int,
    ) {
        require(timeoutSeconds in 0..43_200) { "timeoutSeconds must be between 0 and 43200." }
        runAcknowledgement(action) {
            operations.changeVisibility(context.queueUrl, context.message.receiptHandle, timeoutSeconds)
        }
    }

    private suspend fun runAcknowledgement(
        action: SqsAcknowledgementAction,
        block: suspend () -> Unit,
    ) {
        interceptors.forEach { it.beforeAcknowledgement(context, action) }
        var failure: Throwable? = null
        try {
            block()
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            interceptors.forEach { it.afterAcknowledgement(context, action, failure) }
        }
    }
}
