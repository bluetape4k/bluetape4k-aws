package io.bluetape4k.aws.spring.sns

import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse

object NoopSnsOperations: SnsOperations {

    override suspend fun createTopic(
        topicName: String,
        attributes: Map<String, String>,
    ): String =
        "arn:aws:sns:ap-northeast-2:000000000000:$topicName"

    override suspend fun createFifoTopic(
        topicName: String,
        contentBasedDeduplication: Boolean,
        fifoThroughputScope: SnsFifoThroughputScope?,
        attributes: Map<String, String>,
    ): String =
        createTopic(topicName, attributes)

    override suspend fun createConfiguredTopic(topicName: String): String =
        createTopic(topicName)

    override suspend fun findTopicArn(topicName: String): String? =
        null

    override suspend fun publish(request: SnsPublishRequest): PublishResponse =
        PublishResponse.builder().messageId("noop").build()

    override suspend fun publishSms(request: SnsSmsRequest): PublishResponse =
        PublishResponse.builder().messageId("noop-sms").build()

    override suspend fun confirmSubscription(
        topicArn: String,
        token: String,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse =
        ConfirmSubscriptionResponse.builder()
            .subscriptionArn("$topicArn:confirmed")
            .build()

    override suspend fun confirmSubscription(
        message: SnsHttpMessage,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse =
        confirmSubscription(message.topicArn, message.requireConfirmationToken(), authenticateOnUnsubscribe)
}
