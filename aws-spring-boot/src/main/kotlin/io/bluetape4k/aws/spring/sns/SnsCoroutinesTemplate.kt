package io.bluetape4k.aws.spring.sns

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import software.amazon.awssdk.services.sns.model.PublishResponse

/**
 * AWS SDK v2 [SnsAsyncClient]를 사용하는 코루틴 친화적인 [SnsOperations] 구현입니다.
 *
 * ## 계약
 *
 * `CompletableFuture` SNS API를 suspend 함수로 감싸고 [SnsProperties]의 구성된 주제 속성을
 * 적용하며 AWS SDK 예외를 호출자에게 전파합니다.
 *
 * ```kotlin
 * val topicArn = sns.createConfiguredTopic("orders")
 * sns.publish(SnsPublishRequest(topicArn = topicArn, message = orderJson))
 * ```
 */
class SnsCoroutinesTemplate(
    private val snsAsyncClient: SnsAsyncClient,
    private val properties: SnsProperties,
): SnsOperations {

    override suspend fun createTopic(
        topicName: String,
        attributes: Map<String, String>,
    ): String {
        topicName.requireTopicName()
        return snsAsyncClient.createTopic {
            it.name(topicName)
            if (attributes.isNotEmpty()) {
                it.attributes(attributes)
            }
        }.await().topicArn()
    }

    override suspend fun createFifoTopic(
        topicName: String,
        contentBasedDeduplication: Boolean,
        fifoThroughputScope: SnsFifoThroughputScope?,
        attributes: Map<String, String>,
    ): String {
        topicName.requireTopicName()
        require(topicName.endsWith(".fifo")) {
            "FIFO topic name must end with .fifo."
        }

        val fifoAttributes = buildMap {
            putAll(attributes)
            put("FifoTopic", "true")
            put("ContentBasedDeduplication", contentBasedDeduplication.toString())
            fifoThroughputScope?.let { put("FifoThroughputScope", it.attributeValue) }
        }
        return createTopic(topicName, fifoAttributes)
    }

    override suspend fun createConfiguredTopic(topicName: String): String {
        topicName.requireTopicName()
        val topic = properties.topics[topicName]
            ?: throw IllegalArgumentException("Topic '$topicName' is not configured.")

        return if (topic.fifo) {
            createFifoTopic(
                topicName = topicName,
                contentBasedDeduplication = topic.contentBasedDeduplication,
                fifoThroughputScope = topic.fifoThroughputScope,
                attributes = topic.attributes,
            )
        } else {
            createTopic(topicName, topic.attributes)
        }
    }

    override suspend fun findTopicArn(topicName: String): String? {
        topicName.requireTopicName()
        val suffix = ":$topicName"
        var nextToken: String? = null
        do {
            val response = snsAsyncClient.listTopics {
                nextToken?.let(it::nextToken)
            }.await()
            response.topics().orEmpty()
                .mapNotNull { it.topicArn() }
                .firstOrNull { it.endsWith(suffix) }
                ?.let { return it }
            nextToken = response.nextToken()
        } while (!nextToken.isNullOrBlank())

        return null
    }

    override suspend fun publish(request: SnsPublishRequest): PublishResponse =
        snsAsyncClient.publish {
            it.topicArn(request.topicArn)
            it.message(request.message)
            request.subject?.let(it::subject)
            if (request.messageAttributes.isNotEmpty()) {
                it.messageAttributes(request.messageAttributes)
            }
            request.messageGroupId?.let(it::messageGroupId)
            request.messageDeduplicationId?.let(it::messageDeduplicationId)
        }.await()

    override suspend fun publishSms(request: SnsSmsRequest): PublishResponse =
        snsAsyncClient.publish {
            it.phoneNumber(request.phoneNumber)
            it.message(request.message)
            val attributes = request.toMessageAttributes()
            if (attributes.isNotEmpty()) {
                it.messageAttributes(attributes)
            }
        }.await()

    override suspend fun confirmSubscription(
        topicArn: String,
        token: String,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse {
        topicArn.requireTopicArn()
        require(token.isNotBlank()) { "token must not be blank." }

        return snsAsyncClient.confirmSubscription {
            it.topicArn(topicArn)
            it.token(token)
            it.authenticateOnUnsubscribe(authenticateOnUnsubscribe.toString())
        }.await()
    }

    override suspend fun confirmSubscription(
        message: SnsHttpMessage,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse =
        confirmSubscription(
            topicArn = message.topicArn,
            token = message.requireConfirmationToken(),
            authenticateOnUnsubscribe = authenticateOnUnsubscribe,
        )

    private fun String.requireTopicArn() {
        require(isNotBlank()) { "topicArn must not be blank." }
    }

    private fun String.requireTopicName() {
        require(isNotBlank()) { "topicName must not be blank." }
    }
}
