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
     * AWS SDK 메시지 ID입니다.
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
     * SQS 시스템 속성입니다.
     */
    val attributes: Map<MessageSystemAttributeName, String> get() = message.attributes().orEmpty()

    /**
     * 사용자 정의 메시지 속성입니다.
     */
    val messageAttributes: Map<String, MessageAttributeValue> get() = message.messageAttributes().orEmpty()

    /**
     * FIFO 큐 메시지 그룹 ID입니다.
     */
    val messageGroupId: String? get() = attributes[MessageSystemAttributeName.MESSAGE_GROUP_ID]

    /**
     * FIFO 큐 메시지 중복 제거 ID입니다.
     */
    val messageDeduplicationId: String? get() = attributes[MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID]

    /**
     * FIFO 큐 시퀀스 번호입니다.
     */
    val sequenceNumber: String? get() = attributes[MessageSystemAttributeName.SEQUENCE_NUMBER]

    /**
     * SQS가 기록한 현재 수신 횟수입니다.
     */
    val approximateReceiveCount: Int? get() =
        attributes[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT]?.toIntOrNull()

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
