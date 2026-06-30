package io.bluetape4k.aws.ktor.sns

import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.io.Serializable
import java.net.URI

/**
 * Throughput scope for an SNS FIFO topic.
 */
enum class SnsFifoThroughputScope(val attributeValue: String) {
    /** Computes FIFO throughput at the whole-topic level. */
    TOPIC("Topic"),

    /** Computes FIFO throughput at the message-group level. */
    MESSAGE_GROUP("MessageGroup"),
}

/**
 * Amazon SNS SMS delivery type for the `AWS.SNS.SMS.SMSType` message attribute.
 */
enum class SnsSmsType(val attributeValue: String) {
    /** Cost-optimized, non-critical messages such as marketing notifications. */
    PROMOTIONAL("Promotional"),

    /** Reliability-optimized messages such as one-time passwords or account alerts. */
    TRANSACTIONAL("Transactional"),
}

/**
 * Value object for an SNS topic publish request.
 *
 * ## Contract
 *
 * FIFO-only fields are accepted only for `.fifo` topic ARNs. FIFO topics require
 * a non-blank message group id.
 */
data class SnsPublishRequest(
    val topicArn: String,
    val message: String,
    val subject: String? = null,
    val messageAttributes: Map<String, MessageAttributeValue> = emptyMap(),
    val messageGroupId: String? = null,
    val messageDeduplicationId: String? = null,
): Serializable {
    init {
        topicArn.requireTopicArn()
        require(message.isNotBlank()) { "message must not be blank." }
        subject?.let { require(it.isNotBlank()) { "subject must not be blank." } }
        messageGroupId?.let { require(it.isNotBlank()) { "messageGroupId must not be blank." } }
        messageDeduplicationId?.let { require(it.isNotBlank()) { "messageDeduplicationId must not be blank." } }

        val fifo = topicArn.endsWith(".fifo")
        if (fifo) {
            require(!messageGroupId.isNullOrBlank()) {
                "messageGroupId is required for FIFO topic."
            }
        } else {
            require(messageGroupId == null && messageDeduplicationId == null) {
                "messageGroupId and messageDeduplicationId are not allowed for standard topic."
            }
        }
    }

    companion object {
        private const val serialVersionUID: Long = 5795804169725499052L
    }
}

/**
 * Value object for publishing an SMS message directly to a phone number.
 *
 * ## Contract
 *
 * Maps explicit SMS options to Amazon SNS SMS message attributes. The phone
 * number should use E.164 format, for example `+15550100000`.
 */
data class SnsSmsRequest(
    val phoneNumber: String,
    val message: String,
    val smsType: SnsSmsType? = null,
    val senderId: String? = null,
    val maxPrice: String? = null,
    val originationNumber: String? = null,
    val entityId: String? = null,
    val templateId: String? = null,
    val messageAttributes: Map<String, MessageAttributeValue> = emptyMap(),
): Serializable {

    init {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank." }
        require(message.isNotBlank()) { "message must not be blank." }
        senderId?.let { require(it.isNotBlank()) { "senderId must not be blank." } }
        maxPrice?.let { require(it.isNotBlank()) { "maxPrice must not be blank." } }
        originationNumber?.let { require(it.isNotBlank()) { "originationNumber must not be blank." } }
        entityId?.let { require(it.isNotBlank()) { "entityId must not be blank." } }
        templateId?.let { require(it.isNotBlank()) { "templateId must not be blank." } }
    }

    internal fun toMessageAttributes(): Map<String, MessageAttributeValue> =
        buildMap {
            putAll(messageAttributes)
            smsType?.let { put(SMS_TYPE_ATTRIBUTE, stringAttribute(it.attributeValue)) }
            senderId?.let { put(SENDER_ID_ATTRIBUTE, stringAttribute(it)) }
            maxPrice?.let { put(MAX_PRICE_ATTRIBUTE, stringAttribute(it)) }
            originationNumber?.let { put(ORIGINATION_NUMBER_ATTRIBUTE, stringAttribute(it)) }
            entityId?.let { put(ENTITY_ID_ATTRIBUTE, stringAttribute(it)) }
            templateId?.let { put(TEMPLATE_ID_ATTRIBUTE, stringAttribute(it)) }
        }

    companion object {
        private const val serialVersionUID: Long = -8689631020482660250L

        const val SMS_TYPE_ATTRIBUTE: String = "AWS.SNS.SMS.SMSType"
        const val SENDER_ID_ATTRIBUTE: String = "AWS.SNS.SMS.SenderID"
        const val MAX_PRICE_ATTRIBUTE: String = "AWS.SNS.SMS.MaxPrice"
        const val ORIGINATION_NUMBER_ATTRIBUTE: String = "AWS.MM.SMS.OriginationNumber"
        const val ENTITY_ID_ATTRIBUTE: String = "AWS.MM.SMS.EntityId"
        const val TEMPLATE_ID_ATTRIBUTE: String = "AWS.MM.SMS.TemplateId"

        private fun stringAttribute(value: String): MessageAttributeValue =
            MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(value)
                .build()
    }
}

/**
 * SNS HTTP(S) endpoint message types.
 */
enum class SnsHttpMessageType(val value: String) {
    /** Message delivered to a subscribed HTTP(S) endpoint. */
    NOTIFICATION("Notification"),

    /** Message sent after an HTTP(S) endpoint is subscribed and must be confirmed. */
    SUBSCRIPTION_CONFIRMATION("SubscriptionConfirmation"),

    /** Message sent after an HTTP(S) endpoint is unsubscribed and can be re-confirmed. */
    UNSUBSCRIBE_CONFIRMATION("UnsubscribeConfirmation"),
    ;

    companion object {
        /** Resolves an official SNS HTTP message type value. */
        fun from(value: String): SnsHttpMessageType =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unsupported SNS HTTP message type: $value")
    }
}

/**
 * Parsed SNS HTTP(S) endpoint message.
 *
 * ## Contract
 *
 * This value is untrusted. It exposes SNS signature fields but does not validate
 * the cryptographic signature. Validate the certificate chain, signature, and
 * expected topic ARN before processing notifications or confirming
 * subscriptions.
 */
data class SnsHttpMessage(
    val type: SnsHttpMessageType,
    val messageId: String,
    val topicArn: String,
    val message: String,
    val timestamp: String,
    val signatureVersion: String,
    val signature: String,
    val signingCertUrl: URI,
    val subject: String? = null,
    val token: String? = null,
    val subscribeUrl: URI? = null,
    val unsubscribeUrl: URI? = null,
    val raw: Map<String, String?> = emptyMap(),
): Serializable {

    /** True when this is a notification delivery message. */
    val isNotification: Boolean
        get() = type == SnsHttpMessageType.NOTIFICATION

    /** True when this is a subscription confirmation message. */
    val isSubscriptionConfirmation: Boolean
        get() = type == SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION

    /** True when this is an unsubscribe confirmation message. */
    val isUnsubscribeConfirmation: Boolean
        get() = type == SnsHttpMessageType.UNSUBSCRIBE_CONFIRMATION

    /** True when this message type can carry a subscription confirmation token. */
    val canConfirmSubscription: Boolean
        get() = type == SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION ||
            type == SnsHttpMessageType.UNSUBSCRIBE_CONFIRMATION

    internal fun requireConfirmationToken(): String {
        require(canConfirmSubscription) {
            "SNS HTTP message type ${type.value} cannot confirm a subscription."
        }
        return requireNotNull(token) {
            "SNS HTTP confirmation message token must not be null."
        }
    }

    companion object {
        private const val serialVersionUID: Long = -4940905748188566685L
    }
}

/**
 * Caller-verified SNS HTTP message.
 *
 * ## Contract
 *
 * Create this wrapper only after the caller has validated the SNS signature,
 * certificate chain, expected topic ARN, and replay policy.
 */
class TrustedSnsHttpMessage private constructor(
    val message: SnsHttpMessage,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = -7318834693375493029L

        /**
         * Wraps a caller-verified SNS HTTP message.
         */
        fun fromVerified(message: SnsHttpMessage): TrustedSnsHttpMessage =
            TrustedSnsHttpMessage(message)
    }
}

internal fun String.requireTopicArn() {
    require(isNotBlank()) { "topicArn must not be blank." }
}

internal fun String.requireTopicName() {
    require(isNotBlank()) { "topicName must not be blank." }
}
