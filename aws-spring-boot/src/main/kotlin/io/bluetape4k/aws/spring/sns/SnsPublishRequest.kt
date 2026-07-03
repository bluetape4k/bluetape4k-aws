package io.bluetape4k.aws.spring.sns

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.io.Serializable

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
): Serializable {
    init {
        topicArn.requireNotBlank("topicArn")
        message.requireNotBlank("message")
        subject?.requireNotBlank("subject")
        messageGroupId?.requireNotBlank("messageGroupId")
        messageDeduplicationId?.requireNotBlank("messageDeduplicationId")

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
        private const val serialVersionUID: Long = 1L
    }
}
