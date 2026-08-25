package io.bluetape4k.aws.spring.sns.handlers

import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse

/** SNS subscription lifecycle 메시지의 확인 작업을 명시적으로 제공합니다. */
interface NotificationStatus {
    val topicArn: String
    val token: String

    /** Confirms this subscription through the configured SNS operations client. */
    suspend fun confirmSubscription(
        authenticateOnUnsubscribe: Boolean = true,
    ): ConfirmSubscriptionResponse
}
