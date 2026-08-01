package io.bluetape4k.aws.spring.sns

/**
 * SNS HTTP(S) 엔드포인트 메시지 타입입니다.
 */
enum class SnsHttpMessageType(val value: String) {

    /**
     * 구독한 HTTP(S) 엔드포인트로 전달되는 메시지입니다.
     */
    NOTIFICATION("Notification"),

    /**
     * HTTP(S) 엔드포인트 구독 후 확인을 위해 전송되는 메시지입니다.
     */
    SUBSCRIPTION_CONFIRMATION("SubscriptionConfirmation"),

    /**
     * HTTP(S) 엔드포인트 구독 해제 후 재확인을 위해 전송되는 메시지입니다.
     */
    UNSUBSCRIBE_CONFIRMATION("UnsubscribeConfirmation"),
    ;

    companion object {
        fun from(value: String): SnsHttpMessageType =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unsupported SNS HTTP message type: $value")
    }
}
