package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val operationGuard: () -> Unit = {},
    private val observationRuntime: SqsObservationRuntime? = null,
    private val observationQueueName: String? = null,
) : SqsAcknowledgement {

    private val terminal = AtomicBoolean(false)
    private val inFlightTerminal = AtomicBoolean(false)
    private val operationMutex = Mutex()
    private var operationTail: CompletableDeferred<Unit>? = null

    @Volatile
    private var observationSetupFailure: Throwable? = null

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

    internal suspend fun heartbeat(
        timeoutSeconds: Int,
        onObservationSetupFailure: (Throwable) -> Unit = {},
        onObservationCleanupFailure: (Throwable) -> Unit,
    ) {
        require(timeoutSeconds in 0..43_200) { "timeoutSeconds must be between 0 and 43200." }
        if (terminal.get() || inFlightTerminal.get()) return
        runAcknowledgement(
            action = SqsAcknowledgementAction.CHANGE_VISIBILITY,
            shouldRun = { !terminal.get() && !inFlightTerminal.get() },
            onObservationSetupFailure = onObservationSetupFailure,
            onObservationCleanupFailure = onObservationCleanupFailure,
        ) {
            operations.changeVisibility(context.queueUrl, context.message.receiptHandle, timeoutSeconds)
        }
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
        if (terminal.get() || !inFlightTerminal.compareAndSet(false, true)) {
            return
        }
        try {
            if (!terminal.get()) {
                runAcknowledgement(action, onIoSuccess = { terminal.set(true) }, block = block)
            }
        } finally {
            inFlightTerminal.set(false)
        }
    }

    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun runAcknowledgement(
        action: SqsAcknowledgementAction,
        shouldRun: () -> Boolean = { true },
        onIoSuccess: () -> Unit = {},
        onObservationSetupFailure: ((Throwable) -> Unit)? = null,
        onObservationCleanupFailure: ((Throwable) -> Unit)? = null,
        block: suspend () -> Unit,
    ) {
        if (!shouldRun()) return
        interceptors.forEach { it.beforeAcknowledgement(context, action) }
        val ticket = reserveOperation(shouldRun) ?: return
        var failure: Throwable? = null
        try {
            operationGuard()
            val activeRuntime = observationRuntime.activeOrNull()
            var observedContext: SqsObservationContext? = null
            val observation = try {
                activeRuntime?.let { runtime ->
                    runtime.prepare(acknowledgementObservationContext(action).also { observedContext = it })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Error) {
                throw e
            } catch (e: Throwable) {
                observationSetupFailure = e
                onObservationSetupFailure?.invoke(e)
                throw e
            }
            suspend fun performAcknowledgement() {
                ticket.awaitTurn()
                try {
                    try {
                        block()
                        onIoSuccess()
                        observedContext?.apply {
                            acknowledgementSuccessCount = 1
                            acknowledgementFailureCount = 0
                        }
                    } catch (e: CancellationException) {
                        observedContext?.apply {
                            acknowledgementFailureCount = 1
                            failureStage = "acknowledgement"
                            outcome = SqsObservationOutcome.CANCELLED
                        }
                        throw e
                    } catch (e: Throwable) {
                        observedContext?.apply {
                            acknowledgementFailureCount = 1
                            failureStage = "acknowledgement"
                            outcome = SqsObservationOutcome.ERROR
                        }
                        throw e
                    }
                } finally {
                    ticket.complete()
                }
            }
            if (observation == null) {
                performAcknowledgement()
            } else {
                observation.observe(
                    onSetupFailure = {
                        observationSetupFailure = it
                        onObservationSetupFailure?.invoke(it)
                    },
                    onCleanupFailure = onObservationCleanupFailure,
                ) {
                    performAcknowledgement()
                }
            }
        } catch (e: Throwable) {
            failure = e
        } finally {
            ticket.completeAfterPredecessor()
        }
        val cleanupFailure = runCatchingAcknowledgementFinalization {
            interceptors.forEach { it.afterAcknowledgement(context, action, failure) }
        }
        throwAcknowledgementFailures(failure, cleanupFailure)
    }

    internal fun isObservationSetupFailure(error: Throwable): Boolean = observationSetupFailure === error

    private suspend fun reserveOperation(shouldRun: () -> Boolean): OperationTicket? = operationMutex.withLock {
        if (!shouldRun()) {
            return@withLock null
        }
        val completion = CompletableDeferred<Unit>()
        OperationTicket(operationTail, completion).also { operationTail = completion }
    }

    private fun acknowledgementObservationContext(action: SqsAcknowledgementAction): SqsObservationContext =
        SqsObservationContext(
            SqsObservationMetadata(
                listenerId = context.listenerId,
                queueName = observationQueueName ?: resolveSqsObservationQueueName(context.queueUrl),
                stage = SqsObservationStage.ACKNOWLEDGEMENT,
                batch = false,
                messageId = context.message.messageId,
                messageGroupId = context.message.messageGroupId,
                messageDeduplicationId = context.message.messageDeduplicationId,
                initialAttempt = context.attempt,
                batchSize = 1,
                acknowledgementAction = action,
                delivery = resolveSqsObservationDelivery(context.message.approximateReceiveCount?.toString()),
                queueNameResolved = true,
            ),
        )

    private class OperationTicket(
        private val predecessor: CompletableDeferred<Unit>?,
        private val completion: CompletableDeferred<Unit>,
    ) {
        suspend fun awaitTurn() {
            predecessor?.await()
        }

        fun complete() {
            completion.complete(Unit)
        }

        fun completeAfterPredecessor() {
            if (completion.isCompleted) return
            val previous = predecessor
            if (previous == null || previous.isCompleted) {
                complete()
            } else {
                previous.invokeOnCompletion { complete() }
            }
        }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun runCatchingAcknowledgementFinalization(block: suspend () -> Unit): Throwable? =
    withContext(NonCancellable) {
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
    }

internal fun throwAcknowledgementFailures(primary: Throwable?, cleanup: Throwable?) {
    if (primary == null) {
        cleanup?.let { throw it }
        return
    }
    cleanup?.takeUnless { it === primary }?.let(primary::addSuppressed)
    throw primary
}
