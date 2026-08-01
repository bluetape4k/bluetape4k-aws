package io.bluetape4k.aws.spring.sqs

import java.util.concurrent.atomic.AtomicBoolean

/**
 * `@SqsListener` 호출의 수동 확인 핸들입니다.
 *
 * ## 동작/계약
 *
 * 이 매개변수를 선언한 리스너 메서드는 자동 메시지 삭제를 사용하지 않습니다. 처리가 성공하면
 * [acknowledge]를 호출하고, 지정한 타임아웃 후 메시지를 다시 보이게 하려면 [nack]을 호출하세요.
 */
interface SqsAcknowledgement {

    /**
     * 이 확인이 ack 또는 nack으로 이미 완료되었는지 나타냅니다.
     */
    val completed: Boolean

    /**
     * 큐에서 메시지를 삭제합니다.
     */
    suspend fun acknowledge()

    /**
     * 가시성을 변경하고 메시지를 부정 확인 상태로 표시합니다.
     */
    suspend fun nack(timeoutSeconds: Int = 0)

    /**
     * 확인 상태를 완료하지 않고 메시지 가시성을 변경합니다.
     */
    suspend fun changeVisibility(timeoutSeconds: Int)
}

internal class DefaultSqsAcknowledgement(
    private val context: SqsListenerInvocationContext,
    private val operations: SqsOperations,
    private val interceptors: List<SqsListenerInterceptor>,
) : SqsAcknowledgement {

    private val terminal = AtomicBoolean(false)
    private val inFlightTerminal = AtomicBoolean(false)

    override val completed: Boolean
        get() = terminal.get()

    override suspend fun acknowledge() {
        runTerminalAcknowledgement(SqsAcknowledgementAction.ACK) {
            operations.delete(context.queueUrl, context.message.receiptHandle)
        }
    }

    override suspend fun nack(timeoutSeconds: Int) {
        require(timeoutSeconds in 0..43_200) { "timeoutSeconds must be between 0 and 43200." }
        runTerminalAcknowledgement(SqsAcknowledgementAction.NACK) {
            operations.changeVisibility(context.queueUrl, context.message.receiptHandle, timeoutSeconds)
        }
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

    private suspend fun runTerminalAcknowledgement(
        action: SqsAcknowledgementAction,
        block: suspend () -> Unit,
    ) {
        if (terminal.get()) {
            return
        }
        if (!inFlightTerminal.compareAndSet(false, true)) {
            return
        }
        try {
            runAcknowledgement(action, block)
            terminal.set(true)
        } finally {
            inFlightTerminal.set(false)
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
