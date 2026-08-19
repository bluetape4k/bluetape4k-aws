package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * SNS notification의 사용자 정의 메시지 속성입니다.
 *
 * SNS envelope에 포함된 속성은 SQS 속성과 구분되며, [SnsNotification.headers]에도 함께 노출됩니다.
 */
data class SnsMessageAttribute(
    /** SNS 속성 데이터 타입입니다. */
    val type: String,
    /** SNS 속성 문자열 값입니다. */
    val value: String,
) : Serializable {

    init {
        type.requireNotBlank("type")
    }

    override fun toString(): String =
        "SnsMessageAttribute(type=$type, valuePresent=${value.isNotEmpty()})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SQS로 전달된 SNS `Notification` envelope와 변환된 payload를 함께 보관합니다.
 *
 * [rawEnvelope]는 converter의 보존 옵션이 켜진 경우에만 채워집니다. `messageAttributes`와
 * [headers]는 방어적으로 복사된 불변 snapshot이며, SQS FIFO 및 원본 SQS 메시지 속성은 [sqs]에서
 * 확인할 수 있습니다.
 */
@ConsistentCopyVisibility
data class SnsNotification<T : Any> private constructor(
    /** SNS envelope 타입입니다. 일반적으로 `Notification`입니다. */
    val type: String,
    /** SNS 메시지 ID입니다. */
    val messageId: String,
    /** SNS topic ARN입니다. */
    val topicArn: String,
    /** 변환된 SNS payload입니다. */
    val message: T,
    /** SNS 발행 시각의 원본 문자열입니다. */
    val timestamp: String,
    /** SNS subject입니다. */
    val subject: String?,
    /** SNS signature version입니다. */
    val signatureVersion: String?,
    /** SNS signature 원문입니다. */
    val signature: String?,
    /** SNS signing certificate URL입니다. */
    val signingCertUrl: String?,
    /** SNS envelope 메시지 속성 snapshot입니다. */
    val messageAttributes: Map<String, SnsMessageAttribute>,
    /** 원본 SQS 수신 메시지입니다. */
    val sqs: SqsReceivedMessage,
    /** 보존이 활성화된 경우의 원본 SNS JSON envelope입니다. */
    val rawEnvelope: String?,
    @Suppress("UNUSED_PARAMETER") private val snapshotMarker: Boolean,
) : Serializable {

    /**
     * SNS envelope와 변환 결과를 생성합니다.
     */
    constructor(
        type: String,
        messageId: String,
        topicArn: String,
        message: T,
        timestamp: String,
        subject: String?,
        signatureVersion: String?,
        signature: String?,
        signingCertUrl: String?,
        messageAttributes: Map<String, SnsMessageAttribute>,
        sqs: SqsReceivedMessage,
        rawEnvelope: String?,
    ) : this(
        type = type,
        messageId = messageId,
        topicArn = topicArn,
        message = message,
        timestamp = timestamp,
        subject = subject,
        signatureVersion = signatureVersion,
        signature = signature,
        signingCertUrl = signingCertUrl,
        messageAttributes = messageAttributes.toMap(),
        sqs = sqs,
        rawEnvelope = rawEnvelope,
        snapshotMarker = true,
    )

    init {
        type.requireNotBlank("type")
        messageId.requireNotBlank("messageId")
        topicArn.requireNotBlank("topicArn")
        timestamp.requireNotBlank("timestamp")
        signatureVersion?.requireNotBlank("signatureVersion")
        signature?.requireNotBlank("signature")
        signingCertUrl?.requireNotBlank("signingCertUrl")
        subject?.requireNotBlank("subject")
    }

    /**
     * Spring 메시지 header로 전달할 수 있는 SNS/SQS 메타데이터 snapshot입니다.
     */
    val headers: Map<String, Any>
        get() = buildMap {
            put(SnsNotificationHeaders.TYPE, type)
            put(SnsNotificationHeaders.MESSAGE_ID, messageId)
            put(SnsNotificationHeaders.TOPIC_ARN, topicArn)
            put(SnsNotificationHeaders.TIMESTAMP, timestamp)
            signatureVersion?.let { put(SnsNotificationHeaders.SIGNATURE_VERSION, it) }
            signature?.let { put(SnsNotificationHeaders.SIGNATURE, it) }
            signingCertUrl?.let { put(SnsNotificationHeaders.SIGNING_CERT_URL, it) }
            put(SnsNotificationHeaders.MESSAGE_ATTRIBUTES, messageAttributes)
            subject?.let { put(SnsNotificationHeaders.SUBJECT, it) }
            put(SnsNotificationHeaders.SQS_MESSAGE_ID, sqs.messageId)
            put(SnsNotificationHeaders.SQS_QUEUE_URL, sqs.queueUrl)
            sqs.messageGroupId?.let { put(SnsNotificationHeaders.SQS_MESSAGE_GROUP_ID, it) }
            sqs.messageDeduplicationId?.let { put(SnsNotificationHeaders.SQS_MESSAGE_DEDUPLICATION_ID, it) }
        }

    override fun toString(): String =
        "SnsNotification(type=$type, messageIdPresent=${messageId.isNotEmpty()}, " +
            "topicArnPresent=${topicArn.isNotEmpty()}, payloadType=${message.javaClass.name}, " +
            "attributeCount=${messageAttributes.size}, rawEnvelopePresent=${rawEnvelope != null})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SNS notification metadata를 Spring 메시지 header로 노출할 때 사용하는 키입니다. */
object SnsNotificationHeaders {
    const val TYPE: String = "aws.sns.type"
    const val MESSAGE_ID: String = "aws.sns.messageId"
    const val TOPIC_ARN: String = "aws.sns.topicArn"
    const val SUBJECT: String = "aws.sns.subject"
    const val TIMESTAMP: String = "aws.sns.timestamp"
    const val SIGNATURE_VERSION: String = "aws.sns.signatureVersion"
    const val SIGNATURE: String = "aws.sns.signature"
    const val SIGNING_CERT_URL: String = "aws.sns.signingCertUrl"
    const val MESSAGE_ATTRIBUTES: String = "aws.sns.messageAttributes"
    const val SQS_MESSAGE_ID: String = "aws.sqs.messageId"
    const val SQS_QUEUE_URL: String = "aws.sqs.queueUrl"
    const val SQS_MESSAGE_GROUP_ID: String = "aws.sqs.messageGroupId"
    const val SQS_MESSAGE_DEDUPLICATION_ID: String = "aws.sqs.messageDeduplicationId"
}
