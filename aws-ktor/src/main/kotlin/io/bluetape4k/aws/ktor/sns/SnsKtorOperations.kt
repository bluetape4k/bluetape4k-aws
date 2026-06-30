package io.bluetape4k.aws.ktor.sns

import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import software.amazon.awssdk.services.sns.model.PublishResponse

/**
 * Coroutine-based SNS operations for Ktor applications.
 *
 * ## Contract
 *
 * Provides topic creation, topic lookup, publishing, SMS publishing, and
 * subscription confirmation without exposing `CompletableFuture` to application
 * code.
 */
interface SnsKtorOperations {

    /** Creates a standard topic and returns its topic ARN. */
    suspend fun createTopic(
        topicName: String,
        attributes: Map<String, String> = emptyMap(),
    ): String

    /** Creates a FIFO topic and returns its topic ARN. */
    suspend fun createFifoTopic(
        topicName: String,
        contentBasedDeduplication: Boolean = true,
        fifoThroughputScope: SnsFifoThroughputScope? = null,
        attributes: Map<String, String> = emptyMap(),
    ): String

    /** Creates a topic using [SnsKtorPluginConfig.topics] configuration. */
    suspend fun createConfiguredTopic(topicName: String): String

    /**
     * Finds a topic ARN by topic name.
     *
     * Scans every `ListTopics` page and returns null when no matching topic is found.
     */
    suspend fun findTopicArn(topicName: String): String?

    /** Publishes a message to an SNS topic. */
    suspend fun publish(request: SnsPublishRequest): PublishResponse

    /** Publishes an SMS message directly to a phone number. */
    suspend fun publishSms(request: SnsSmsRequest): PublishResponse

    /** Confirms an HTTP(S) endpoint subscription using explicit topic ARN and token. */
    suspend fun confirmSubscription(
        topicArn: String,
        token: String,
        authenticateOnUnsubscribe: Boolean = true,
    ): ConfirmSubscriptionResponse

    /**
     * Confirms or re-confirms an HTTP(S) endpoint subscription from a caller-verified SNS message.
     */
    suspend fun confirmSubscription(
        message: TrustedSnsHttpMessage,
        authenticateOnUnsubscribe: Boolean = true,
    ): ConfirmSubscriptionResponse
}
