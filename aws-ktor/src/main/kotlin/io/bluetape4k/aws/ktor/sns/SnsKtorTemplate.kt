package io.bluetape4k.aws.ktor.sns

import io.bluetape4k.aws.sns.createFIFOTopic
import io.bluetape4k.aws.sns.createTopic
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import software.amazon.awssdk.services.sns.model.PublishResponse

/**
 * [SnsAsyncClient]를 사용하는 코루틴 친화적인 [SnsKtorOperations] 구현입니다.
 *
 * ## 계약
 *
 * 주제 생성에는 기존 `aws-java` 코루틴 도우미를 재사용합니다. 게시, 목록, 확인 경로의
 * Ktor 요청 및 신뢰 계약은 이 모듈에 속하므로 `CompletableFuture.await()`를 직접 사용합니다.
 */
class SnsKtorTemplate(
    private val snsAsyncClient: SnsAsyncClient,
    private val topics: Map<String, SnsKtorTopic> = emptyMap(),
): SnsKtorOperations {

    override suspend fun createTopic(
        topicName: String,
        attributes: Map<String, String>,
    ): String {
        topicName.requireTopicName()
        return snsAsyncClient.createTopic(topicName, attributes).topicArn()
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
        return snsAsyncClient.createFIFOTopic(topicName, fifoAttributes).topicArn()
    }

    override suspend fun createConfiguredTopic(topicName: String): String {
        topicName.requireTopicName()
        val topic = topics[topicName]
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
        message: TrustedSnsHttpMessage,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse =
        confirmSubscription(
            topicArn = message.message.topicArn,
            token = message.message.requireConfirmationToken(),
            authenticateOnUnsubscribe = authenticateOnUnsubscribe,
        )
}
