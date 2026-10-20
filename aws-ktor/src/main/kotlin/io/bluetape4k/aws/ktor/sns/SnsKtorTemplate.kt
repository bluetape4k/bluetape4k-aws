package io.bluetape4k.aws.ktor.sns

import aws.sdk.kotlin.services.sns.SnsClient
import aws.sdk.kotlin.services.sns.model.ConfirmSubscriptionRequest
import aws.sdk.kotlin.services.sns.model.ConfirmSubscriptionResponse
import aws.sdk.kotlin.services.sns.model.CreateTopicRequest
import aws.sdk.kotlin.services.sns.model.ListTopicsRequest
import aws.sdk.kotlin.services.sns.model.PublishRequest
import aws.sdk.kotlin.services.sns.model.PublishResponse
import aws.sdk.kotlin.services.sns.model.MessageAttributeValue
import io.bluetape4k.support.requireNotBlank

/**
 * [SnsClient]를 사용하는 코루틴 중심 [SnsKtorOperations] 구현입니다.
 *
 * ## 계약
 *
 * SNS 요청과 응답은 AWS Kotlin SDK 모델을 사용합니다. SMS 옵션은 사용자 속성보다 우선하여
 * 동일한 이름의 표준 SMS 속성을 덮어씁니다.
 */
class SnsKtorTemplate(
    private val snsClient: SnsClient,
    private val topics: Map<String, SnsKtorTopic> = emptyMap(),
): SnsKtorOperations {

    override suspend fun createTopic(
        topicName: String,
        attributes: Map<String, String>,
    ): String {
        topicName.requireTopicName()
        return snsClient.createTopic(
            CreateTopicRequest {
                name = topicName
                this.attributes = attributes
            }
        ).topicArn ?: error("SNS createTopic returned no topic ARN.")
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
        return snsClient.createTopic(
            CreateTopicRequest {
                name = topicName
                this.attributes = fifoAttributes
            }
        ).topicArn ?: error("SNS createTopic returned no topic ARN.")
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
            val response = snsClient.listTopics(
                ListTopicsRequest { this.nextToken = nextToken }
            )

            response.topics.orEmpty()
                .firstNotNullOfOrNull { topic -> topic.topicArn?.takeIf { it.endsWith(suffix) } }
                ?.let { return it }

            nextToken = response.nextToken
        } while (!nextToken.isNullOrBlank())

        return null
    }

    override suspend fun publish(request: SnsPublishRequest): PublishResponse =
        snsClient.publish(
            PublishRequest {
                topicArn = request.topicArn
                message = request.message
                subject = request.subject
                messageAttributes = request.messageAttributes.toSdkMessageAttributes().takeIf { it.isNotEmpty() }
                messageGroupId = request.messageGroupId
                messageDeduplicationId = request.messageDeduplicationId
            }
        )

    override suspend fun publishSms(request: SnsSmsRequest): PublishResponse =
        snsClient.publish(
            PublishRequest {
                phoneNumber = request.phoneNumber
                message = request.message
                messageAttributes = request.toMessageAttributes()
                    .toSdkMessageAttributes()
                    .takeIf { it.isNotEmpty() }
            }
        )

    override suspend fun confirmSubscription(
        topicArn: String,
        token: String,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse {
        topicArn.requireTopicArn()
        token.requireNotBlank("token")

        return snsClient.confirmSubscription(
            ConfirmSubscriptionRequest {
                this.topicArn = topicArn
                this.token = token
                this.authenticateOnUnsubscribe = authenticateOnUnsubscribe.toString()
            }
        )
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

    private fun Map<String, SnsMessageAttribute>.toSdkMessageAttributes(): Map<String, MessageAttributeValue> =
        mapValues { (_, attribute) ->
            MessageAttributeValue {
                dataType = attribute.dataType
                stringValue = attribute.stringValue
                binaryValue = attribute.binaryValueForSdk
            }
        }
}
