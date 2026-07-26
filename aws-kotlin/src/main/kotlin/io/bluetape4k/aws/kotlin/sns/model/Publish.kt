package io.bluetape4k.aws.kotlin.sns.model

import aws.sdk.kotlin.services.sns.model.MessageAttributeValue
import aws.sdk.kotlin.services.sns.model.PublishBatchRequestEntry
import aws.sdk.kotlin.services.sns.model.PublishRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [PublishRequest] that publishes [message] to the topic identified by [topicArn].
 *
 * ```
 * val request = publishRequestOf(
 *    topicArn = "arn:aws:sns:ap-northeast-2:123456789012:MyTopic",
 *    message = "Hello, SNS!"
 * )
 * client.publish(request)
 * ```
 *
 * @param topicArn ARN of the topic to publish to.
 * @param phoneNumber Phone number to publish to.
 * @param message Message to publish.
 * @param subject Message subject.
 * @param messageAttributes Message attributes.
 * @param messageDeduplicationId Message deduplication ID.
 * @param messageGroupId Message group ID.
 * @param builder Lambda for applying additional settings to [PublishRequest.Builder].
 * @return A [PublishRequest] instance.
 */
inline fun publishRequestOf(
    topicArn: String,
    phoneNumber: String,
    message: String,
    subject: String? = null,
    messageAttributes: Map<String, MessageAttributeValue>? = null,
    messageDeduplicationId: String? = null,
    messageGroupId: String? = null,
    crossinline builder: PublishRequest.Builder.() -> Unit = {},
): PublishRequest {
    topicArn.requireNotBlank("topicArn")
    phoneNumber.requireNotBlank("phoneNumber")
    message.requireNotBlank("message")

    return PublishRequest {
        this.topicArn = topicArn
        this.phoneNumber = phoneNumber
        this.message = message
        subject?.let { this.subject = it }
        messageAttributes?.let { this.messageAttributes = it }
        messageDeduplicationId?.let { this.messageDeduplicationId = it }
        messageGroupId?.let { this.messageGroupId = it }

        builder()
    }
}

/**
 * Creates a [PublishBatchRequestEntry] for an SNS batch publish request.
 *
 * ```
 * val entry = publishBatchRequestEntryOf(
 *     id = "msg-001",
 *     message = "Hello, SNS!",
 *     messageGroupId = "group1"
 * )
 * ```
 *
 * @param id Unique identifier for the batch entry.
 * @param message Message content to publish.
 * @param messageAttributes Message attribute map.
 * @param messageDeduplicationId Message deduplication ID for FIFO topics.
 * @param messageGroupId Message group ID for FIFO topics.
 * @param builder Lambda for applying additional settings to [PublishBatchRequestEntry.Builder].
 * @return A [PublishBatchRequestEntry] instance.
 */
inline fun publishBatchRequestEntryOf(
    id: String,
    message: String,
    messageAttributes: Map<String, MessageAttributeValue>? = null,
    messageDeduplicationId: String? = null,
    messageGroupId: String? = null,
    crossinline builder: PublishBatchRequestEntry.Builder.() -> Unit = {},
): PublishBatchRequestEntry {
    id.requireNotBlank("id")
    message.requireNotBlank("message")

    return PublishBatchRequestEntry {
        this.id = id
        this.message = message
        messageAttributes?.let { this.messageAttributes = it }
        messageDeduplicationId?.let { this.messageDeduplicationId = it }
        messageGroupId?.let { this.messageGroupId = it }

        builder()
    }
}
