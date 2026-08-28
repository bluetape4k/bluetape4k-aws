package io.bluetape4k.aws.spring.sns.handlers

import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse

/** SNS subscription lifecycle 메시지의 대상 정보와 확인 작업을 명시적으로 제공합니다. */
interface NotificationStatus {
    /** 확인 대상 SNS topic ARN입니다. */
    val topicArn: String

    /** subscription 확인에 사용하는 SNS token입니다. */
    val token: String

    /**
     * 구성된 [io.bluetape4k.aws.spring.sns.SnsOperations] client를 통해 이 subscription을 확인합니다.
     *
     * adapter는 이 함수를 자동으로 호출하지 않으며, handler가 필요한 시점에 명시적으로 호출해야
     * 합니다. `UnsubscribeConfirmation`에서는 `authenticateOnUnsubscribe` 값을 AWS 요청의
     * `AuthenticateOnUnsubscribe` 옵션으로 전달합니다.
     *
     * @param authenticateOnUnsubscribe `UnsubscribeConfirmation`을 확인할 때 AWS 인증 옵션을
     * 적용할지 여부입니다.
     * @return AWS SDK의 [ConfirmSubscriptionResponse]입니다.
     */
    suspend fun confirmSubscription(
        authenticateOnUnsubscribe: Boolean = true,
    ): ConfirmSubscriptionResponse
}
