package io.bluetape4k.aws.spring.sqs

import org.springframework.boot.json.JsonParserFactory
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/** SNS envelope가 손상되었을 때 적용할 변환 정책입니다. */
enum class SnsMalformedEnvelopeStrategy {
    /** 일반 SQS 본문 변환으로 되돌립니다. */
    FALLBACK_TO_SQS,

    /** 원본 SQS 본문을 보존한 예외를 발생시킵니다. */
    THROW,
}

/**
 * SNS `Notification` envelope 변환 실패입니다.
 *
 * 원본 본문은 로그나 예외 메시지에 포함하지 않고 [rawEnvelope]로만 명시적으로 제공합니다.
 */
class SnsMessageConversionException(
    /** 실패한 원본 SNS envelope입니다. */
    val rawEnvelope: String,
    cause: Throwable,
) : IllegalArgumentException("SNS notification envelope conversion failed", cause)

/**
 * SQS 본문에 포함된 SNS `Notification` envelope를 풀어 typed payload로 변환합니다.
 *
 * `Notification`이 아닌 일반 SQS 본문은 기존 Jackson 변환과 동일하게 처리합니다. 손상된
 * `Notification`은 [malformedEnvelopeStrategy]에 따라 일반 SQS 변환으로 되돌리거나 예외를
 * 발생시키며, [preserveRawEnvelope]가 켜져 있으면 성공한 notification에도 원본 envelope를
 * 보존합니다.
 */
class SnsMessageConverter(
    private val objectMapper: ObjectMapper,
    private val malformedEnvelopeStrategy: SnsMalformedEnvelopeStrategy =
        SnsMalformedEnvelopeStrategy.FALLBACK_TO_SQS,
    private val preserveRawEnvelope: Boolean = true,
) : SqsMessageConverter {

    override fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any =
        convert(message, targetType, null)

    override fun convert(message: SqsReceivedMessage, targetType: Class<*>, genericType: Type?): Any {
        val envelope = parseNotification(message) ?: return convertBody(message.body, targetType)
        val payloadType = payloadType(genericType, targetType)
        val payload = convertPayload(envelope.message, payloadType)

        return if (targetType == SnsNotification::class.java) {
            notification(envelope, payload, message)
        } else {
            payload
        }
    }

    /** [message]를 [payloadType]으로 변환한 SNS notification을 반환합니다. */
    fun <T : Any> convertNotification(
        message: SqsReceivedMessage,
        payloadType: Class<T>,
    ): SnsNotification<T> {
        val envelope = parseNotification(message)
            ?: throw SnsMessageConversionException(
                rawEnvelope = message.body,
                cause = IllegalArgumentException("SQS body is not an SNS Notification envelope"),
            )
        @Suppress("UNCHECKED_CAST")
        val payload = convertPayload(envelope.message, payloadType) as T
        return notification(envelope, payload, message)
    }

    private fun convertBody(body: String, targetType: Class<*>): Any =
        if (targetType == String::class.java) body else objectMapper.readValue(body, targetType)

    private fun convertPayload(body: String, targetType: Class<*>): Any =
        convertBody(body, targetType)

    @Suppress("ReturnCount")
    private fun parseNotification(message: SqsReceivedMessage): ParsedNotification? {
        val values = try {
            JsonParserFactory.getJsonParser().parseMap(message.body)
        } catch (_: RuntimeException) {
            return null
        }

        val type = values.optionalString(TYPE_KEY) ?: return null
        if (type != NOTIFICATION_TYPE) return null

        return try {
            ParsedNotification(
                type = type,
                messageId = values.requireString(MESSAGE_ID_KEY),
                topicArn = values.requireString(TOPIC_ARN_KEY),
                message = values.requireString(MESSAGE_KEY),
                timestamp = values.requireString(TIMESTAMP_KEY),
                subject = values.optionalString(SUBJECT_KEY),
                signatureVersion = values.optionalString(SIGNATURE_VERSION_KEY),
                signature = values.optionalString(SIGNATURE_KEY),
                signingCertUrl = values.optionalString(SIGNING_CERT_URL_KEY),
                messageAttributes = values.messageAttributes(),
            )
        } catch (e: IllegalArgumentException) {
            when (malformedEnvelopeStrategy) {
                SnsMalformedEnvelopeStrategy.FALLBACK_TO_SQS -> null
                SnsMalformedEnvelopeStrategy.THROW -> throw SnsMessageConversionException(message.body, e)
            }
        }
    }

    private fun <T : Any> notification(
        envelope: ParsedNotification,
        payload: T,
        sqs: SqsReceivedMessage,
    ): SnsNotification<T> = SnsNotification(
        type = envelope.type,
        messageId = envelope.messageId,
        topicArn = envelope.topicArn,
        message = payload,
        timestamp = envelope.timestamp,
        subject = envelope.subject,
        signatureVersion = envelope.signatureVersion,
        signature = envelope.signature,
        signingCertUrl = envelope.signingCertUrl,
        messageAttributes = envelope.messageAttributes,
        sqs = sqs,
        rawEnvelope = sqs.body.takeIf { preserveRawEnvelope },
    )

    private fun payloadType(genericType: Type?, targetType: Class<*>): Class<*> =
        if (targetType == SnsNotification::class.java) {
            val parameterized = genericType as? ParameterizedType
            parameterized?.actualTypeArguments
                ?.singleOrNull()
                ?.let { it as? Class<*> }
                ?: String::class.java
        } else {
            targetType
        }

    private data class ParsedNotification(
        val type: String,
        val messageId: String,
        val topicArn: String,
        val message: String,
        val timestamp: String,
        val subject: String?,
        val signatureVersion: String?,
        val signature: String?,
        val signingCertUrl: String?,
        val messageAttributes: Map<String, SnsMessageAttribute>,
    )

    private fun Map<String, Any>.requireString(key: String): String =
        optionalString(key) ?: throw IllegalArgumentException("SNS notification requires string field $key")

    private fun Map<String, Any>.optionalString(key: String): String? =
        (this[key] ?: return null).let { value ->
            require(value is String) { "SNS notification field $key must be a string" }
            value.takeIf { it.isNotBlank() }
        }

    @Suppress("ThrowsCount")
    private fun Map<String, Any>.messageAttributes(): Map<String, SnsMessageAttribute> {
        val raw = this["MessageAttributes"] ?: return emptyMap()
        val attributes = raw as? Map<*, *>
            ?: throw IllegalArgumentException("SNS notification MessageAttributes must be an object")

        return attributes.entries.associate { entry ->
            val name = entry.key as? String
                ?: throw IllegalArgumentException("SNS notification attribute name must be a string")
            val value = entry.value as? Map<*, *>
                ?: throw IllegalArgumentException("SNS notification attribute $name must be an object")
            val type = value["Type"] as? String
                ?: throw IllegalArgumentException("SNS notification attribute $name requires Type")
            val text = value["Value"] as? String
                ?: throw IllegalArgumentException("SNS notification attribute $name requires Value")
            name to SnsMessageAttribute(type = type, value = text)
        }
    }

    private companion object {
        const val NOTIFICATION_TYPE: String = "Notification"
        const val TYPE_KEY: String = "Type"
        const val MESSAGE_ID_KEY: String = "MessageId"
        const val TOPIC_ARN_KEY: String = "TopicArn"
        const val MESSAGE_KEY: String = "Message"
        const val TIMESTAMP_KEY: String = "Timestamp"
        const val SUBJECT_KEY: String = "Subject"
        const val SIGNATURE_VERSION_KEY: String = "SignatureVersion"
        const val SIGNATURE_KEY: String = "Signature"
        const val SIGNING_CERT_URL_KEY: String = "SigningCertURL"
    }
}
