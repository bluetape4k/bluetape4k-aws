package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.sqs.SnsMessageAttribute
import java.io.Serializable
import java.net.URI

/**
 * 파싱된 SNS HTTP(S) 엔드포인트 메시지입니다.
 *
 * 이 값 객체는 SNS 서명 필드를 노출하지만 암호학적 서명을 검증하지 않습니다. 알림을 처리하거나
 * 구독을 확인하기 전에 서명과 예상 topic ARN을 검증하세요.
 */
data class SnsHttpMessage(
    val type: SnsHttpMessageType,
    val messageId: String,
    val topicArn: String,
    val message: String,
    val timestamp: String,
    val signatureVersion: String,
    val signature: String,
    val signingCertUrl: URI,
    val subject: String? = null,
    val token: String? = null,
    val subscribeUrl: URI? = null,
    val unsubscribeUrl: URI? = null,
    val raw: Map<String, Any?> = emptyMap(),
): Serializable {

    val isNotification: Boolean
        get() = type == SnsHttpMessageType.NOTIFICATION

    val isSubscriptionConfirmation: Boolean
        get() = type == SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION

    val isUnsubscribeConfirmation: Boolean
        get() = type == SnsHttpMessageType.UNSUBSCRIBE_CONFIRMATION

    val canConfirmSubscription: Boolean
        get() = type == SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION ||
            type == SnsHttpMessageType.UNSUBSCRIBE_CONFIRMATION

    /** SNS와 SQS adapter가 공유하는 `SnsMessageAttribute` 타입의 방어적 snapshot입니다. */
    val messageAttributes: Map<String, SnsMessageAttribute>
        get() {
            val attributes = raw["MessageAttributes"] ?: return emptyMap()
            require(attributes is Map<*, *>) {
                "SNS HTTP message field MessageAttributes must be an object."
            }
            return attributes.entries.associate { (key, value) ->
                require(key is String && key.isNotBlank()) {
                    "SNS HTTP message attribute names must be non-blank strings."
                }
                require(value is Map<*, *>) {
                    "SNS HTTP message attribute $key must be an object."
                }
                val attributeType = value["Type"]
                val attributeValue = value["Value"]
                require(attributeType is String && attributeType.isNotBlank()) {
                    "SNS HTTP message attribute $key Type must be a non-blank string."
                }
                require(attributeValue is String) {
                    "SNS HTTP message attribute $key Value must be a string."
                }
                key to SnsMessageAttribute(attributeType, attributeValue)
            }
        }

    internal fun requireConfirmationToken(): String {
        require(canConfirmSubscription) {
            "SNS HTTP message type ${type.value} cannot confirm a subscription."
        }
        return requireNotNull(token) {
            "SNS HTTP confirmation message token must not be null."
        }
    }

    companion object {
        private const val serialVersionUID: Long = -3596492942929261432L
    }
}
