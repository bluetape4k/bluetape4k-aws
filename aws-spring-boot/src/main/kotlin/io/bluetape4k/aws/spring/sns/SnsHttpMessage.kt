package io.bluetape4k.aws.spring.sns

import java.io.Serializable
import java.net.URI

/**
 * Parsed SNS HTTP(S) endpoint message.
 *
 * This value object exposes SNS signature fields but does not validate the
 * cryptographic signature. Validate the signature and expected topic ARN before
 * processing notifications or confirming subscriptions.
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
