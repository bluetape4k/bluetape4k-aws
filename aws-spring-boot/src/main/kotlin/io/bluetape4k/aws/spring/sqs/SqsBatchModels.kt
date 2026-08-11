package io.bluetape4k.aws.spring.sqs

/**
 * batch acknowledgement가 수행한 SQS 작업입니다.
 */
enum class SqsBatchAcknowledgementOperation {
    ACKNOWLEDGE,
    NACK,
    CHANGE_VISIBILITY,
}

/**
 * batch acknowledgement 작업 결과의 전체 상태입니다.
 */
enum class SqsBatchAcknowledgementStatus {
    SUCCESS,
    PARTIAL_FAILURE,
    FAILURE,
}

/**
 * 하나의 메시지에 대한 batch acknowledgement 실패 정보입니다.
 *
 * receipt handle과 본문은 보안상 결과에 포함하지 않습니다.
 */
data class SqsBatchAcknowledgementFailure(
    val messageId: String,
    val code: String?,
    val detail: String?,
    val senderFault: Boolean,
) {
    override fun toString(): String = "SqsBatchAcknowledgementFailure(code=$code, senderFault=$senderFault)"
}

/**
 * 공개 batch acknowledgement 결과입니다.
 */
data class SqsBatchAcknowledgementResult(
    val operation: SqsBatchAcknowledgementOperation,
    val status: SqsBatchAcknowledgementStatus,
    val successfulMessageIds: List<String>,
    val failed: List<SqsBatchAcknowledgementFailure>,
) {
    override fun toString(): String =
        "SqsBatchAcknowledgementResult(operation=$operation, status=$status, " +
            "successfulCount=${successfulMessageIds.size}, failedCount=${failed.size})"
}

/**
 * 단건 fallback과 AWS DeleteMessageBatch가 공유하는 삭제 결과입니다.
 */
data class SqsBatchDeleteResult(
    val successfulEntryIds: List<String>,
    val failed: List<SqsBatchDeleteFailure>,
) {
    override fun toString(): String =
        "SqsBatchDeleteResult(successfulCount=${successfulEntryIds.size}, failedCount=${failed.size})"
}

/**
 * DeleteMessageBatch 항목별 실패 정보입니다.
 */
data class SqsBatchDeleteFailure(
    val entryId: String,
    val code: String?,
    val detail: String?,
    val senderFault: Boolean,
) {
    override fun toString(): String = "SqsBatchDeleteFailure(code=$code, senderFault=$senderFault)"
}

/**
 * ChangeMessageVisibilityBatch 요청입니다.
 */
data class SqsChangeVisibilityRequest(
    val messageId: String,
    val receiptHandle: String,
    val timeoutSeconds: Int,
) {
    override fun toString(): String =
        "SqsChangeVisibilityRequest(messageIdPresent=${messageId.isNotBlank()}, timeoutSeconds=$timeoutSeconds)"
}

/**
 * ChangeMessageVisibilityBatch 결과입니다.
 */
data class SqsBatchVisibilityResult(
    val successfulMessageIds: List<String>,
    val failed: List<SqsBatchAcknowledgementFailure>,
) {
    override fun toString(): String =
        "SqsBatchVisibilityResult(successfulCount=${successfulMessageIds.size}, failedCount=${failed.size})"
}

/**
 * DeleteMessageBatch 응답 entry 집합이 요청과 일치하지 않을 때 발생하는 오류입니다.
 */
class SqsBatchDeleteProtocolException(
    val submittedEntryIds: List<String>,
    val responseEntryIds: List<String>,
) : IllegalStateException("SQS DeleteMessageBatch response did not match submitted entries")

/**
 * ChangeMessageVisibilityBatch 응답 entry 집합이 요청과 일치하지 않을 때 발생하는 오류입니다.
 */
class SqsBatchVisibilityProtocolException(
    val submittedEntryIds: List<String>,
    val responseEntryIds: List<String>,
) : IllegalStateException("SQS ChangeMessageVisibilityBatch response did not match submitted entries")

internal const val MAX_SQS_BATCH_SIZE: Int = 10
internal const val MAX_SQS_VISIBILITY_TIMEOUT_SECONDS: Int = 43_200

internal fun requireBatchSize(size: Int, message: String = "batch delete supports at most 10 messages") {
    require(size <= MAX_SQS_BATCH_SIZE) { message }
}

internal fun requireVisibilityTimeout(timeoutSeconds: Int) {
    require(timeoutSeconds in 0..MAX_SQS_VISIBILITY_TIMEOUT_SECONDS) {
        "timeoutSeconds must be between 0 and 43200"
    }
}
