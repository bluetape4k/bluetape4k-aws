package io.bluetape4k.aws.spring.sqs

import software.amazon.awssdk.services.sqs.model.Message

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
) {
    /**
     * 메시지 본문.
     */
    val body: String get() = message.body()

    /**
     * 삭제나 visibility timeout 변경에 사용할 receipt handle.
     */
    val receiptHandle: String get() = message.receiptHandle()
}
