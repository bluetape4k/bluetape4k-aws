package io.bluetape4k.aws.spring.sns

/**
 * SNS HTTP(S) endpoint message types.
 */
enum class SnsHttpMessageType(val value: String) {

    /**
     * Message delivered to a subscribed HTTP(S) endpoint.
     */
    NOTIFICATION("Notification"),

    /**
     * Message sent after an HTTP(S) endpoint is subscribed and must be confirmed.
     */
    SUBSCRIPTION_CONFIRMATION("SubscriptionConfirmation"),

    /**
     * Message sent after an HTTP(S) endpoint is unsubscribed and can be re-confirmed.
     */
    UNSUBSCRIBE_CONFIRMATION("UnsubscribeConfirmation"),
    ;

    companion object {
        fun from(value: String): SnsHttpMessageType =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unsupported SNS HTTP message type: $value")
    }
}
