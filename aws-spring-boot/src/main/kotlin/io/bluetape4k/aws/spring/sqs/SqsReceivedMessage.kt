package io.bluetape4k.aws.spring.sqs

import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import java.io.Serializable

/**
 * Message wrapper that keeps a received SQS message together with its queue URL.
 */
data class SqsReceivedMessage(
    /**
     * Queue URL from which the message was received.
     */
    val queueUrl: String,
    /**
     * Original AWS SDK SQS message.
     */
    val message: Message,
): Serializable {
    /**
     * AWS SDK message ID.
     */
    val messageId: String get() = message.messageId()

    /**
     * Message body.
     */
    val body: String get() = message.body()

    /**
     * Receipt handle used to delete the message or change its visibility timeout.
     */
    val receiptHandle: String get() = message.receiptHandle()

    /**
     * SQS system attributes.
     */
    val attributes: Map<MessageSystemAttributeName, String> get() = message.attributes().orEmpty()

    /**
     * User-defined message attributes.
     */
    val messageAttributes: Map<String, MessageAttributeValue> get() = message.messageAttributes().orEmpty()

    /**
     * FIFO queue message group ID.
     */
    val messageGroupId: String? get() = attributes[MessageSystemAttributeName.MESSAGE_GROUP_ID]

    /**
     * FIFO queue message deduplication ID.
     */
    val messageDeduplicationId: String? get() = attributes[MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID]

    /**
     * FIFO queue sequence number.
     */
    val sequenceNumber: String? get() = attributes[MessageSystemAttributeName.SEQUENCE_NUMBER]

    /**
     * Current receive count recorded by SQS.
     */
    val approximateReceiveCount: Int? get() =
        attributes[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT]?.toIntOrNull()

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
