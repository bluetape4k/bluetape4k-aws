package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.sns.handlers.NotificationStatus
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse

/** 기존 [SnsOperations]를 사용해 [NotificationStatus] 계약을 구현하는 기본 상태 객체입니다. */
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
