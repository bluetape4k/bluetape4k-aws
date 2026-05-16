package io.bluetape4k.aws.spring.sns

import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.io.Serializable

/**
 * Value object for publishing an SMS message directly to a phone number.
 *
 * ## Contract
 *
 * Maps explicit SMS options to the Amazon SNS SMS message attributes used by
 * the `Publish` API. The phone number should use E.164 format, for example
 * `+15550100000`.
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
        private const val serialVersionUID: Long = 8992453079138558590L

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
