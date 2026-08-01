package io.bluetape4k.aws.spring.sqs

import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import java.io.Serializable

/**
 * 수신한 SQS 메시지와 큐 URL을 함께 보관하는 메시지 래퍼.
 */
data class SqsReceivedMessage(
    /**
     * 메시지를 수신한 큐 URL.
     */
    val queueUrl: String,
    /**
     * AWS SDK 원본 SQS 메시지.
     */
    val message: Message,
): Serializable {
    /**
     * AWS SDK message ID.
     */
    val messageId: String get() = message.messageId()

    /**
     * 메시지 본문.
     */
    val body: String get() = message.body()

    /**
     * 삭제나 visibility timeout 변경에 사용할 receipt handle.
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
