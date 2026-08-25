package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.sns.handlers.NotificationStatus
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse

/** Default [NotificationStatus] implementation backed by existing SNS operations. */
internal class SnsNotificationStatus(
    private val message: SnsHttpMessage,
    private val operations: SnsOperations,
) : NotificationStatus {
    override val topicArn: String
        get() = message.topicArn

    override val token: String
        get() = message.requireConfirmationToken()

    override suspend fun confirmSubscription(authenticateOnUnsubscribe: Boolean): ConfirmSubscriptionResponse =
        operations.confirmSubscription(message, authenticateOnUnsubscribe)
}
