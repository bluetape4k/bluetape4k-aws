package io.bluetape4k.aws.spring.sns.consumer

import io.bluetape4k.aws.spring.sns.SnsFifoThroughputScope
import io.bluetape4k.aws.spring.sns.SnsHttpMessage
import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import io.bluetape4k.aws.spring.sns.SnsSmsRequest
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import software.amazon.awssdk.services.sns.model.PublishResponse

/** 새 batch default method를 알지 못하는 기존 consumer 구현체의 source 모양입니다. */
class LegacySnsOperationsFixture: SnsOperations {
    override suspend fun createTopic(
        topicName: String,
        attributes: Map<String, String>,
    ): String = topicName

    override suspend fun createFifoTopic(
        topicName: String,
        contentBasedDeduplication: Boolean,
        fifoThroughputScope: SnsFifoThroughputScope?,
        attributes: Map<String, String>,
    ): String = topicName

    override suspend fun createConfiguredTopic(topicName: String): String = topicName

    override suspend fun findTopicArn(topicName: String): String? = topicName

    override suspend fun publish(request: SnsPublishRequest): PublishResponse =
        PublishResponse.builder().messageId("legacy").build()

    override suspend fun publishSms(request: SnsSmsRequest): PublishResponse =
        PublishResponse.builder().messageId("legacy-sms").build()

    override suspend fun confirmSubscription(
        topicArn: String,
        token: String,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse =
        ConfirmSubscriptionResponse.builder().subscriptionArn("legacy").build()

    override suspend fun confirmSubscription(
        message: SnsHttpMessage,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse =
        ConfirmSubscriptionResponse.builder().subscriptionArn("legacy-message").build()
}
