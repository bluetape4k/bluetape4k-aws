package io.bluetape4k.aws.spring.sns

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.PublishResponse

/**
 * Coroutine-friendly [SnsOperations] implementation backed by AWS SDK v2 [SnsAsyncClient].
 *
 * ## Contract
 *
 * Wraps `CompletableFuture` SNS APIs with suspending functions, applies
 * configured topic properties from [SnsProperties], and lets AWS SDK exceptions
 * propagate to callers.
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

    private fun String.requireTopicName() {
        require(isNotBlank()) { "topicName must not be blank." }
    }
}
