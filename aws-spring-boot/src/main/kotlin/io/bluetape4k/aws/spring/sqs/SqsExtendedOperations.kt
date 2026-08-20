package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** SQS 기본 계약과 분리된 opt-in Extended Client 작업 계약입니다. */
interface SqsExtendedClientOperations {

    suspend fun send(request: SqsExtendedSendRequest): SqsExtendedSendResult

    suspend fun receive(
        queueUrl: String,
        maxMessages: Int = 1,
        waitTimeSeconds: Int = 20,
        visibilityTimeoutSeconds: Int = 30,
    ): List<SqsExtendedReceivedMessage>

    suspend fun acknowledge(message: SqsExtendedReceivedMessage): SqsExtendedAcknowledgementResult

    suspend fun cleanup(handle: SqsExtendedCleanupHandle): SqsExtendedCleanupResult

    fun receiveFlow(
        queueUrl: String,
        maxMessages: Int = 1,
        waitTimeSeconds: Int = 20,
        visibilityTimeoutSeconds: Int = 30,
    ): Flow<SqsExtendedReceivedMessage>

    suspend fun drain(timeout: Duration? = null): SqsExtendedDrainResult
}

/** drain 결과를 표현하는 불변 값입니다. */
data class SqsExtendedDrainResult(
    val activeAtStart: Int,
    val completed: Int,
    val timedOut: Boolean,
) {
    init {
        require(activeAtStart >= 0) { "activeAtStart must not be negative." }
        require(completed in 0..activeAtStart) { "completed must be within activeAtStart." }
        require(timedOut || completed == activeAtStart) {
            "a completed drain must account for every active operation."
        }
    }
}

/** 전체 SQS 요청 필드를 보존하는 additive capability marker입니다. */
interface SqsFullRequestOperations : SqsOperations
