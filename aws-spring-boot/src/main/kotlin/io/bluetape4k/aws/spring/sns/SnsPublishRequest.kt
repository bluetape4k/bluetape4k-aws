package io.bluetape4k.aws.spring.sns

import software.amazon.awssdk.services.sns.model.MessageAttributeValue

/**
 * SNS publish 요청 값 객체.
 *
 * 같은 타입의 positional parameter 실수를 줄이기 위해 topic ARN, 본문,
 * FIFO 전용 필드를 하나의 명명된 값으로 묶습니다.
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
