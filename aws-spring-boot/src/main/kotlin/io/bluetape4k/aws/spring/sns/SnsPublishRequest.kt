package io.bluetape4k.aws.spring.sns

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.io.Serializable

/**
 * SNS 게시 요청 값 객체입니다.
 *
 * ## 계약
 *
 * 타입이 같은 위치 인수의 실수를 피하도록 topic ARN, 메시지 본문, 선택적 제목, 속성,
 * FIFO 필드를 명명된 값으로 묶습니다. FIFO 주제에는 `messageGroupId`가 필요하며
 * 표준 주제는 FIFO 전용 필드를 거부합니다.
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
