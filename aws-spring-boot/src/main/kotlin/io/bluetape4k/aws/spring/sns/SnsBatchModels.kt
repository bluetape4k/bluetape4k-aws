package io.bluetape4k.aws.spring.sns

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.io.Serializable

/** SNS 배치 발행의 개별 입력 항목입니다. */
@ConsistentCopyVisibility
data class SnsPublishBatchEntry private constructor(
    val id: String,
    val message: String,
    val subject: String? = null,
    val messageAttributes: Map<String, MessageAttributeValue>,
    val messageGroupId: String? = null,
    val messageDeduplicationId: String? = null,
    @Suppress("UNUSED_PARAMETER") private val snapshotMarker: Boolean,
): Serializable {

    constructor(
        id: String,
        message: String,
        subject: String? = null,
        messageAttributes: Map<String, MessageAttributeValue> = emptyMap(),
        messageGroupId: String? = null,
        messageDeduplicationId: String? = null,
    ) : this(
        id = id,
        message = message,
        subject = subject,
        messageAttributes = messageAttributes.toMap(),
        messageGroupId = messageGroupId,
        messageDeduplicationId = messageDeduplicationId,
        snapshotMarker = true,
    )

    init {
        id.requireNotBlank("id")
        message.requireNotBlank("message")
        subject?.requireNotBlank("subject")
        messageGroupId?.requireNotBlank("messageGroupId")
        messageDeduplicationId?.requireNotBlank("messageDeduplicationId")
    }

    override fun toString(): String =
        "SnsPublishBatchEntry(idPresent=${id.isNotEmpty()}, messagePresent=${message.isNotEmpty()}, " +
            "attributeCount=${messageAttributes.size}, fifo=${messageGroupId != null})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SNS 배치 발행 전체 요청입니다. 입력 크기는 실행 계층에서 10개 단위로 분할합니다. */
@ConsistentCopyVisibility
data class SnsPublishBatchRequest private constructor(
    val topicArn: String,
    val entries: List<SnsPublishBatchEntry>,
    @Suppress("UNUSED_PARAMETER") private val snapshotMarker: Boolean,
): Serializable {

    constructor(
        topicArn: String,
        entries: List<SnsPublishBatchEntry>,
    ) : this(topicArn = topicArn, entries = entries.toList(), snapshotMarker = true)

    init {
        topicArn.requireNotBlank("topicArn")
        val ids = this.entries.map { it.id }
        require(ids.size == ids.toSet().size) { "entries must have distinct ids." }

        val fifo = topicArn.endsWith(".fifo")
        this.entries.forEach { entry ->
            if (fifo) {
                require(!entry.messageGroupId.isNullOrBlank()) {
                    "messageGroupId is required for FIFO topic."
                }
            } else {
                require(entry.messageGroupId == null && entry.messageDeduplicationId == null) {
                    "messageGroupId and messageDeduplicationId are not allowed for standard topic."
                }
            }
        }
    }

    override fun toString(): String =
        "SnsPublishBatchRequest(topicPresent=${topicArn.isNotEmpty()}, entryCount=${entries.size})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SNS 배치 발행의 성공·실패 항목 결과입니다. */
@ConsistentCopyVisibility
data class SnsPublishBatchResult private constructor(
    val successful: List<SnsPublishBatchSuccess>,
    val failed: List<SnsPublishBatchFailure>,
    @Suppress("UNUSED_PARAMETER") private val snapshotMarker: Boolean,
): Serializable {

    constructor(
        successful: List<SnsPublishBatchSuccess>,
        failed: List<SnsPublishBatchFailure>,
    ) : this(successful = successful.toList(), failed = failed.toList(), snapshotMarker = true)
    val isFullySuccessful: Boolean get() = failed.isEmpty()

    override fun toString(): String =
        "SnsPublishBatchResult(successfulCount=${successful.size}, failedCount=${failed.size})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SNS 배치 발행 성공 항목입니다. */
data class SnsPublishBatchSuccess(
    val entryId: String,
    val messageId: String,
    val sequenceNumber: String? = null,
): Serializable {

    init {
        entryId.requireNotBlank("entryId")
        messageId.requireNotBlank("messageId")
        sequenceNumber?.requireNotBlank("sequenceNumber")
    }

    override fun toString(): String = "SnsPublishBatchSuccess(entryIdPresent=${entryId.isNotEmpty()})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SNS 배치 발행 실패 항목입니다. */
data class SnsPublishBatchFailure(
    val entryId: String,
    val code: String?,
    val message: String?,
    val senderFault: Boolean,
): Serializable {

    init {
        entryId.requireNotBlank("entryId")
    }

    override fun toString(): String =
        "SnsPublishBatchFailure(entryIdPresent=${entryId.isNotEmpty()}, " +
            "codePresent=${!code.isNullOrEmpty()}, senderFault=$senderFault)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SNS 배치 실행 동시성 옵션입니다. */
data class SnsBatchExecutionOptions(
    val maxInFlightBatches: Int = 1,
): Serializable {

    init {
        require(maxInFlightBatches > 0) { "maxInFlightBatches must be positive." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
