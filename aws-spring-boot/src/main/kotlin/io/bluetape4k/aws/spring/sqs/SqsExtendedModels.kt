@file:Suppress("MagicNumber")

package io.bluetape4k.aws.spring.sqs

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

/** 외부에 안전하게 노출하는 SQS message attribute입니다. */
class SqsExtendedMessageAttribute private constructor(
    val dataType: String,
    val stringValue: String?,
    private val binaryBytes: ByteArray?,
) {
    val binaryValue: ByteArray?
        get() = binaryBytes?.clone()

    override fun toString(): String =
        "SqsExtendedMessageAttribute(dataTypePresent=${dataType.isNotBlank()}, stringValuePresent=${stringValue != null}, binaryValuePresent=${binaryBytes != null})"

    internal companion object {
        fun create(value: MessageAttributeValue): SqsExtendedMessageAttribute {
            val rawDataType = value.dataType()
            val rawStringValue = value.stringValue()
            require(rawDataType.isNotBlank() && rawDataType.length <= 256) {
                "message attribute dataType must be 1..256 characters."
            }
            require(rawDataType.all { it in '\u0020'..'\u007e' && it != '\r' && it != '\n' }) {
                "message attribute dataType contains an invalid character."
            }
            require(rawStringValue == null || rawStringValue.none { it == '\r' || it == '\n' }) {
                "message attribute stringValue contains an invalid character."
            }
            return SqsExtendedMessageAttribute(
                dataType = rawDataType,
                stringValue = rawStringValue,
                binaryBytes = value.binaryValue()?.asByteArray()?.clone(),
            )
        }

        fun binary(dataType: String, bytes: ByteArray): SqsExtendedMessageAttribute =
            SqsExtendedMessageAttribute(dataType, null, bytes.clone())
    }
}

/** Extended send 입력입니다. raw request의 안전한 수명은 adapter 내부에 둡니다. */
data class SqsExtendedSendRequest(
    val request: SqsSendRequest,
    val contentType: String? = null,
    val idempotencyKey: String? = null,
) {
    init {
        require(contentType == null || contentType.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "contentType contains an invalid character."
        }
        require(idempotencyKey == null || idempotencyKey.isNotBlank()) {
            "idempotencyKey must not be blank when provided."
        }
    }

    override fun toString(): String =
        "SqsExtendedSendRequest(contentTypePresent=${contentType != null}, idempotencyKeyPresent=${idempotencyKey != null})"
}

internal class SqsExtendedAcknowledgementToken(
    internal val queueUrl: String,
    internal val receiptHandle: String,
    internal val pointerDigest: String?,
    internal val policyFingerprint: String?,
)

/** 수신 본문과 safe metadata만 외부로 전달하는 identity-bound 메시지입니다. */
class SqsExtendedReceivedMessage private constructor(
    internal val rawMessage: SqsReceivedMessage,
    val body: String,
    val messageId: String,
    val messageAttributes: Map<String, SqsExtendedMessageAttribute>,
    val systemAttributes: Map<String, String>,
    val contentType: String?,
    val pointer: SqsExtendedClientPointer?,
    val duplicateAfterCleanup: Boolean,
    private val acknowledgement: SqsExtendedAcknowledgementToken,
) {
    internal fun acknowledgementToken(): SqsExtendedAcknowledgementToken = acknowledgement

    override fun toString(): String =
        "SqsExtendedReceivedMessage(contentTypePresent=${contentType != null}, pointerPresent=${pointer != null}, duplicateAfterCleanup=$duplicateAfterCleanup)"

    internal companion object {
        fun create(
            message: SqsReceivedMessage,
            body: String,
            contentType: String?,
            pointer: SqsExtendedClientPointer?,
            duplicateAfterCleanup: Boolean,
            acknowledgement: SqsExtendedAcknowledgementToken,
        ): SqsExtendedReceivedMessage =
            SqsExtendedReceivedMessage(
                rawMessage = message,
                body = body,
                messageId = message.messageId,
                messageAttributes = message.messageAttributes.mapValues { (_, value) ->
                    SqsExtendedMessageAttribute.create(value)
                }.toMap(),
                systemAttributes = message.attributes.mapKeys { (name, _) -> name.name }.toMap(),
                contentType = contentType,
                pointer = pointer,
                duplicateAfterCleanup = duplicateAfterCleanup,
                acknowledgement = acknowledgement,
            )
    }
}

data class SqsExtendedSendResponse(
    val messageId: String?,
    val sequenceNumber: String?,
    val md5OfMessageBody: String?,
    val md5OfMessageAttributes: String?,
) {
    override fun toString(): String =
        "SqsExtendedSendResponse(messageIdPresent=${messageId != null}, sequenceNumberPresent=${sequenceNumber != null}, bodyDigestPresent=${md5OfMessageBody != null}, attributesDigestPresent=${md5OfMessageAttributes != null})"
}

class SqsExtendedSendResult private constructor(
    val response: SqsExtendedSendResponse,
    val offloaded: Boolean,
    val pointer: SqsExtendedClientPointer?,
) {
    init {
        require(offloaded == (pointer != null)) { "offloaded and pointer presence must agree." }
    }

    override fun toString(): String =
        "SqsExtendedSendResult(offloaded=$offloaded, pointerPresent=${pointer != null})"

    internal companion object {
        fun create(
            response: SqsExtendedSendResponse,
            offloaded: Boolean,
            pointer: SqsExtendedClientPointer?,
        ): SqsExtendedSendResult = SqsExtendedSendResult(response, offloaded, pointer)
    }
}

class SqsExtendedAcknowledgementResult private constructor(
    val sqsDeleted: Boolean,
    val payloadDeleted: Boolean,
    val cleanupRequired: Boolean,
    val pointer: SqsExtendedClientPointer?,
    val failureKind: SqsExtendedFailureKind? = null,
    val retryable: Boolean = false,
    val cleanupHandle: SqsExtendedCleanupHandle? = null,
) {
    override fun toString(): String =
        "SqsExtendedAcknowledgementResult(sqsDeleted=$sqsDeleted, payloadDeleted=$payloadDeleted, cleanupRequired=$cleanupRequired, failureKind=$failureKind, retryable=$retryable, cleanupHandlePresent=${cleanupHandle != null})"

    internal companion object {
        fun create(
            sqsDeleted: Boolean,
            payloadDeleted: Boolean,
            cleanupRequired: Boolean,
            pointer: SqsExtendedClientPointer?,
            failureKind: SqsExtendedFailureKind? = null,
            retryable: Boolean = false,
            cleanupHandle: SqsExtendedCleanupHandle? = null,
        ): SqsExtendedAcknowledgementResult {
            require(sqsDeleted) { "an acknowledgement result requires SQS deletion." }
            require(cleanupRequired == (cleanupHandle != null)) {
                "cleanupRequired and cleanupHandle presence must agree."
            }
            require(!payloadDeleted || pointer != null) { "payload deletion requires a pointer." }
            require(!cleanupRequired || (!payloadDeleted && pointer != null)) {
                "cleanup retry requires an undeleted pointer."
            }
            require(!cleanupRequired || (failureKind == SqsExtendedFailureKind.S3_DELETE && retryable)) {
                "cleanup retry requires a retryable S3 delete failure."
            }
            require(cleanupRequired || (failureKind == null && !retryable && cleanupHandle == null)) {
                "successful acknowledgement cannot carry failure state."
            }
            require(pointer != null || (!payloadDeleted && !cleanupRequired)) {
                "pointer-less acknowledgement cannot report payload work."
            }
            return SqsExtendedAcknowledgementResult(
                sqsDeleted,
                payloadDeleted,
                cleanupRequired,
                pointer,
                failureKind,
                retryable,
                cleanupHandle,
            )
        }
    }
}

class SqsExtendedCleanupHandle private constructor(
    internal val pointer: SqsExtendedClientPointer,
    internal val queueUrl: String,
    internal val policyFingerprint: String,
    internal val markerKey: String?,
    internal val pointerDigest: String?,
) {
    override fun toString(): String = "SqsExtendedCleanupHandle(available=true)"

    internal companion object {
        fun create(
            pointer: SqsExtendedClientPointer,
            queueUrl: String,
            policyFingerprint: String,
            markerKey: String?,
            pointerDigest: String? = null,
        ): SqsExtendedCleanupHandle =
            SqsExtendedCleanupHandle(pointer, queueUrl, policyFingerprint, markerKey, pointerDigest)
    }
}

class SqsExtendedCleanupResult private constructor(
    val deleted: Boolean,
    val cleanupRequired: Boolean,
    val failureKind: SqsExtendedFailureKind? = null,
    val retryable: Boolean = false,
    val diagnosticCode: String? = null,
    val cleanupHandle: SqsExtendedCleanupHandle? = null,
) {
    override fun toString(): String =
        "SqsExtendedCleanupResult(deleted=$deleted, cleanupRequired=$cleanupRequired, failureKind=$failureKind, retryable=$retryable, cleanupHandlePresent=${cleanupHandle != null})"

    internal companion object {
        fun create(
            deleted: Boolean,
            cleanupRequired: Boolean,
            failureKind: SqsExtendedFailureKind? = null,
            retryable: Boolean = false,
            diagnostic: SqsExtendedDiagnosticCode? = null,
            cleanupHandle: SqsExtendedCleanupHandle? = null,
        ): SqsExtendedCleanupResult {
            require(cleanupRequired == (cleanupHandle != null)) {
                "cleanupRequired and cleanupHandle presence must agree."
            }
            require(!deleted || (!cleanupRequired && failureKind == null && !retryable && cleanupHandle == null)) {
                "successful cleanup cannot carry failure state."
            }
            require(!cleanupRequired || (!deleted && failureKind == SqsExtendedFailureKind.S3_DELETE && retryable)) {
                "cleanup retry requires a retryable S3 delete failure."
            }
            require(deleted || cleanupRequired) { "cleanup result must be deleted or retryable." }
            require(!deleted || diagnostic == null) { "successful cleanup cannot carry a diagnostic." }
            require(!cleanupRequired || diagnostic == SqsExtendedDiagnosticCode.S3_DELETE) {
                "cleanup retry requires the S3 delete diagnostic."
            }
            return SqsExtendedCleanupResult(
                deleted,
                cleanupRequired,
                failureKind,
                retryable,
                diagnostic?.value,
                cleanupHandle,
            )
        }
    }
}
