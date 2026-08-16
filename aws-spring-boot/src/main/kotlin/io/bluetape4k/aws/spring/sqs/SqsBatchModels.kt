package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

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

/** 자동 배치 전송의 실패 처리 전략입니다. */
enum class SendBatchFailureStrategy {
    RETURN,
    THROW,
}

/** 자동 배치 호출의 전체 결과 상태입니다. */
enum class SqsBatchResultStatus {
    SUCCESS,
    PARTIAL_FAILURE,
    FAILURE,
}

/** 자동 배치 항목 실패의 안전한 분류입니다. */
enum class SqsBatchFailureKind {
    SERVICE,
    TRANSPORT,
}

/** 자동 배치 전송의 개별 입력 항목입니다. */
@ConsistentCopyVisibility
data class SqsBatchSendEntry private constructor(
    val entryId: String,
    val request: SqsSendRequest,
    @Suppress("UNUSED_PARAMETER") private val snapshotMarker: Boolean,
) : Serializable {

    init {
        validateAutomaticBatchEntryId(entryId)
    }

    constructor(entryId: String, request: SqsSendRequest) : this(
        entryId = entryId,
        request = request.copy(messageAttributes = request.messageAttributes.toMap()),
        snapshotMarker = true,
    )

    override fun toString(): String = "SqsBatchSendEntry(request=<redacted>)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 자동 배치 삭제의 개별 입력 항목입니다. */
@ConsistentCopyVisibility
data class SqsBatchDeleteEntry private constructor(
    val entryId: String,
    val queueUrl: String,
    val receiptHandle: String,
    @Suppress("UNUSED_PARAMETER") private val validatedMarker: Boolean,
) : Serializable {

    init {
        validateAutomaticBatchEntryId(entryId)
    }

    constructor(entryId: String, queueUrl: String, receiptHandle: String) : this(
        entryId = entryId,
        queueUrl = queueUrl.requireNotBlank("queueUrl"),
        receiptHandle = receiptHandle.requireNotBlank("receiptHandle"),
        validatedMarker = true,
    )

    override fun toString(): String = "SqsBatchDeleteEntry(<redacted>)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 자동 배치 항목의 안전한 실패 결과입니다. */
@ConsistentCopyVisibility
data class SqsBatchEntryFailure private constructor(
    val entryId: String,
    val kind: SqsBatchFailureKind,
    val code: String?,
    @Suppress("UNUSED_PARAMETER") private val normalizedMarker: Boolean,
) : Serializable {

    init {
        validateAutomaticBatchEntryId(entryId)
        require(kind == SqsBatchFailureKind.SERVICE || code == null) {
            "code must be null for transport failure."
        }
    }

    constructor(entryId: String, kind: SqsBatchFailureKind, code: String?) : this(
        entryId = entryId,
        kind = kind,
        code = if (kind == SqsBatchFailureKind.TRANSPORT) null else normalizeAutomaticBatchErrorCode(code),
        normalizedMarker = true,
    )

    override fun toString(): String = "SqsBatchEntryFailure(kind=$kind)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 자동 배치 전송의 성공 결과입니다. */
@ConsistentCopyVisibility
data class SqsBatchSendSuccess private constructor(
    val entryId: String,
    val messageId: String,
    val sequenceNumber: String?,
    @Suppress("UNUSED_PARAMETER") private val validatedMarker: Boolean,
) : Serializable {

    init {
        validateAutomaticBatchEntryId(entryId)
    }

    constructor(entryId: String, messageId: String, sequenceNumber: String?) : this(
        entryId = entryId,
        messageId = messageId.requireNotBlank("messageId"),
        sequenceNumber = sequenceNumber?.requireNotBlank("sequenceNumber"),
        validatedMarker = true,
    )

    override fun toString(): String = "SqsBatchSendSuccess(<redacted>)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 자동 배치 전송의 정규화된 결과입니다. */
@ConsistentCopyVisibility
data class SqsSendManyResult private constructor(
    val successful: List<SqsBatchSendSuccess>,
    val failed: List<SqsBatchEntryFailure>,
    @Suppress("UNUSED_PARAMETER") private val snapshotMarker: Boolean,
) : Serializable {

    init {
        validateAutomaticBatchResultIds(successful.map { it.entryId }, failed.map { it.entryId })
    }

    val status: SqsBatchResultStatus
        get() = automaticBatchResultStatus(successful.size, failed.size)

    constructor(
        successful: List<SqsBatchSendSuccess>,
        failed: List<SqsBatchEntryFailure>,
    ) : this(successful.toList(), failed.toList(), true)

    override fun toString(): String =
        "SqsSendManyResult(status=$status, successful=${successful.size}, failed=${failed.size})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 자동 배치 삭제의 정규화된 결과입니다. */
@ConsistentCopyVisibility
data class SqsDeleteManyResult private constructor(
    val successfulEntryIds: List<String>,
    val failed: List<SqsBatchEntryFailure>,
    @Suppress("UNUSED_PARAMETER") private val snapshotMarker: Boolean,
) : Serializable {

    init {
        validateAutomaticBatchResultIds(successfulEntryIds, failed.map { it.entryId })
    }

    val status: SqsBatchResultStatus
        get() = automaticBatchResultStatus(successfulEntryIds.size, failed.size)

    constructor(
        successfulEntryIds: List<String>,
        failed: List<SqsBatchEntryFailure>,
    ) : this(successfulEntryIds.toList(), failed.toList(), true)

    override fun toString(): String =
        "SqsDeleteManyResult(status=$status, successful=${successfulEntryIds.size}, failed=${failed.size})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal sealed interface SqsBatchOutcome {
    val entryId: String

    data class SendSuccess(
        override val entryId: String,
        val messageId: String?,
        val sequenceNumber: String?,
    ) : SqsBatchOutcome

    data class DeleteSuccess(
        override val entryId: String,
    ) : SqsBatchOutcome

    data class Failure(
        val failure: SqsBatchEntryFailure,
    ) : SqsBatchOutcome {
        override val entryId: String get() = failure.entryId
    }
}

internal object BatchResultNormalizer {

    fun send(
        expectedEntryIds: Collection<String>,
        outcomes: Collection<SqsBatchOutcome>,
    ): SqsSendManyResult {
        val expected = validateExpectedAutomaticBatchIds(expectedEntryIds)
        val snapshot = outcomes.toList()
        validateAutomaticBatchProtocol(expected, snapshot)
        val byId = snapshot.associateBy(SqsBatchOutcome::entryId)
        val successful = ArrayList<SqsBatchSendSuccess>(expected.size)
        val failed = ArrayList<SqsBatchEntryFailure>()

        expected.forEach { entryId ->
            when (val outcome = byId.getValue(entryId)) {
                is SqsBatchOutcome.SendSuccess -> {
                    val messageId = outcome.messageId
                    val sequenceNumber = outcome.sequenceNumber
                    if (messageId.isNullOrBlank() || sequenceNumber?.isBlank() == true) {
                        throw SqsBatchProtocolException.from(expected, snapshot.map(SqsBatchOutcome::entryId))
                    }
                    successful += SqsBatchSendSuccess(entryId, messageId, sequenceNumber)
                }
                is SqsBatchOutcome.Failure -> failed += outcome.failure
                is SqsBatchOutcome.DeleteSuccess ->
                    throw SqsBatchProtocolException.from(expected, snapshot.map(SqsBatchOutcome::entryId))
            }
        }
        return SqsSendManyResult(successful, failed)
    }

    fun delete(
        expectedEntryIds: Collection<String>,
        outcomes: Collection<SqsBatchOutcome>,
    ): SqsDeleteManyResult {
        val expected = validateExpectedAutomaticBatchIds(expectedEntryIds)
        val snapshot = outcomes.toList()
        validateAutomaticBatchProtocol(expected, snapshot)
        val byId = snapshot.associateBy(SqsBatchOutcome::entryId)
        val successful = ArrayList<String>(expected.size)
        val failed = ArrayList<SqsBatchEntryFailure>()

        expected.forEach { entryId ->
            when (val outcome = byId.getValue(entryId)) {
                is SqsBatchOutcome.DeleteSuccess -> successful += entryId
                is SqsBatchOutcome.Failure -> failed += outcome.failure
                is SqsBatchOutcome.SendSuccess ->
                    throw SqsBatchProtocolException.from(expected, snapshot.map(SqsBatchOutcome::entryId))
            }
        }
        return SqsDeleteManyResult(successful, failed)
    }
}

private val AUTOMATIC_BATCH_ENTRY_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,80}")
private val AUTOMATIC_BATCH_ERROR_CODE_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

private fun validateAutomaticBatchEntryId(entryId: String) {
    entryId.requireNotBlank("entryId")
    require(AUTOMATIC_BATCH_ENTRY_ID_PATTERN.matches(entryId)) {
        "entryId must contain 1 to 80 allowed characters."
    }
}

private fun normalizeAutomaticBatchErrorCode(code: String?): String =
    code?.takeIf(AUTOMATIC_BATCH_ERROR_CODE_PATTERN::matches) ?: "UNKNOWN"

private fun validateAutomaticBatchResultIds(successfulIds: Collection<String>, failedIds: Collection<String>) {
    val ids = (successfulIds + failedIds).onEach(::validateAutomaticBatchEntryId)
    require(ids.size == ids.toSet().size) { "result entryIds must be distinct." }
}

private fun automaticBatchResultStatus(successfulCount: Int, failedCount: Int): SqsBatchResultStatus = when {
    failedCount == 0 -> SqsBatchResultStatus.SUCCESS
    successfulCount == 0 -> SqsBatchResultStatus.FAILURE
    else -> SqsBatchResultStatus.PARTIAL_FAILURE
}

private fun validateExpectedAutomaticBatchIds(entryIds: Collection<String>): List<String> =
    entryIds.toList().also { ids ->
        ids.forEach(::validateAutomaticBatchEntryId)
        require(ids.size == ids.toSet().size) { "expected entryIds must be distinct." }
    }

private fun validateAutomaticBatchProtocol(
    expectedEntryIds: List<String>,
    outcomes: List<SqsBatchOutcome>,
) {
    val responseEntryIds = outcomes.map(SqsBatchOutcome::entryId)
    val expectedSet = expectedEntryIds.toSet()
    val responseCounts = responseEntryIds.groupingBy { it }.eachCount()
    val protocolMatches = responseEntryIds.none { it !in expectedSet } &&
        responseCounts.values.all { it == 1 } &&
        expectedEntryIds.all { it in responseCounts }
    if (!protocolMatches) {
        throw SqsBatchProtocolException.from(expectedEntryIds, responseEntryIds)
    }
}
