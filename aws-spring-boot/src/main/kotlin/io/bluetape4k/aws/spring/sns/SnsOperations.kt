package io.bluetape4k.aws.spring.sns

import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse

/**
 * Coroutine-based SNS operations for Spring applications.
 *
 * ## Contract
 *
 * Provides topic creation, configured-topic creation, topic lookup, and message
 * publishing without exposing `CompletableFuture` to application code.
 *
 * ```kotlin
 * class OrderTopic(private val sns: SnsOperations) {
 *     suspend fun publish(orderJson: String, topicArn: String) {
 *         sns.publish(SnsPublishRequest(topicArn = topicArn, message = orderJson))
 *     }
 * }
 * ```
 */
interface SnsOperations {

    /**
     * Creates a standard topic and returns its topic ARN.
     */
    suspend fun createTopic(
        topicName: String,
        attributes: Map<String, String> = emptyMap(),
    ): String

    /**
     * Creates a FIFO topic and returns its topic ARN.
     */
    suspend fun createFifoTopic(
        topicName: String,
        contentBasedDeduplication: Boolean = true,
        fifoThroughputScope: SnsFifoThroughputScope? = null,
        attributes: Map<String, String> = emptyMap(),
    ): String

    /**
     * Creates a topic using `bluetape4k.aws.sns.topics` configuration.
     */
    suspend fun createConfiguredTopic(topicName: String): String

    /**
     * Finds a topic ARN by topic name.
     *
     * Scans every `ListTopics` page and returns null when no matching topic is
     * found.
     */
    suspend fun findTopicArn(topicName: String): String?

    /**
     * Publishes a message to an SNS topic.
     */
    suspend fun publish(request: SnsPublishRequest): PublishResponse

    /**
     * Publishes an SMS message directly to a phone number.
     */
    suspend fun publishSms(request: SnsSmsRequest): PublishResponse

    /**
     * Confirms an HTTP(S) endpoint subscription using the SNS confirmation token.
     */
    suspend fun confirmSubscription(
        topicArn: String,
        token: String,
        authenticateOnUnsubscribe: Boolean = true,
    ): ConfirmSubscriptionResponse

    /**
     * Confirms or re-confirms an HTTP(S) endpoint subscription from a parsed SNS
     * confirmation message.
     */
    suspend fun confirmSubscription(
        message: SnsHttpMessage,
        authenticateOnUnsubscribe: Boolean = true,
    ): ConfirmSubscriptionResponse
}
