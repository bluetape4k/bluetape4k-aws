package io.bluetape4k.aws.spring.sns

import software.amazon.awssdk.services.sns.model.MessageAttributeValue

/**
 * Value object for an SNS publish request.
 *
 * ## Contract
 *
 * Groups the topic ARN, message body, optional subject, attributes, and FIFO
 * fields into a named value to avoid same-typed positional mistakes. FIFO
 * topics require `messageGroupId`; standard topics reject FIFO-only fields.
 *
 * ```kotlin
 * val request = SnsPublishRequest(
 *     topicArn = topicArn,
 *     subject = "Order accepted",
 *     message = orderJson,
 * )
 * ```
 */
data class SnsPublishRequest(
    val topicArn: String,
    val message: String,
    val subject: String? = null,
    val messageAttributes: Map<String, MessageAttributeValue> = emptyMap(),
    val messageGroupId: String? = null,
    val messageDeduplicationId: String? = null,
) {
    init {
        require(topicArn.isNotBlank()) { "topicArn must not be blank." }
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
}
