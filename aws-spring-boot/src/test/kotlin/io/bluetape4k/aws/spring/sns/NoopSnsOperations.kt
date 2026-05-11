package io.bluetape4k.aws.spring.sns

import software.amazon.awssdk.services.sns.model.PublishResponse

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
}
