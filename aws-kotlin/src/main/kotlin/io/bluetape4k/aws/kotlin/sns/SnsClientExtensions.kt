package io.bluetape4k.aws.kotlin.sns

import aws.sdk.kotlin.services.sns.SnsClient
import aws.sdk.kotlin.services.sns.checkIfPhoneNumberIsOptedOut
import aws.sdk.kotlin.services.sns.createPlatformEndpoint
import aws.sdk.kotlin.services.sns.createTopic
import aws.sdk.kotlin.services.sns.deleteTopic
import aws.sdk.kotlin.services.sns.model.CheckIfPhoneNumberIsOptedOutRequest
import aws.sdk.kotlin.services.sns.model.CheckIfPhoneNumberIsOptedOutResponse
import aws.sdk.kotlin.services.sns.model.CreatePlatformEndpointRequest
import aws.sdk.kotlin.services.sns.model.CreatePlatformEndpointResponse
import aws.sdk.kotlin.services.sns.model.CreateTopicRequest
import aws.sdk.kotlin.services.sns.model.CreateTopicResponse
import aws.sdk.kotlin.services.sns.model.DeleteTopicRequest
import aws.sdk.kotlin.services.sns.model.DeleteTopicResponse
import aws.sdk.kotlin.services.sns.model.PublishBatchRequest
import aws.sdk.kotlin.services.sns.model.PublishBatchRequestEntry
import aws.sdk.kotlin.services.sns.model.PublishBatchResponse
import aws.sdk.kotlin.services.sns.model.PublishRequest
import aws.sdk.kotlin.services.sns.model.PublishResponse
import aws.sdk.kotlin.services.sns.model.SubscribeRequest
import aws.sdk.kotlin.services.sns.model.SubscribeResponse
import aws.sdk.kotlin.services.sns.model.UnsubscribeRequest
import aws.sdk.kotlin.services.sns.model.UnsubscribeResponse
import aws.sdk.kotlin.services.sns.publish
import aws.sdk.kotlin.services.sns.publishBatch
import aws.sdk.kotlin.services.sns.subscribe
import aws.sdk.kotlin.services.sns.unsubscribe
import io.bluetape4k.apache.endsWithIgnoreCase
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a platform endpoint.
 *
 * ```
 * val response = snsClient.createPlatformEndpoint(token, platformApplicationArn) {
 *   customUserData = "customUserData"
 * }
 * ```
 *
 * @param token Device token.
 * @param platformApplicationArn ARN of the platform application.
 * @param builder Lambda for configuring platform endpoint creation.
 * @return A [CreatePlatformEndpointResponse] instance.
 */
suspend inline fun SnsClient.createPlatformEndpoint(
    token: String,
    platformApplicationArn: String,
    crossinline builder: CreatePlatformEndpointRequest.Builder.() -> Unit = {},
): CreatePlatformEndpointResponse {
    token.requireNotBlank("token")
    platformApplicationArn.requireNotBlank("platformApplicationArn")

    return createPlatformEndpoint {
        this.token = token
        this.platformApplicationArn = platformApplicationArn

        builder()
    }
}

/**
 * Creates an SNS topic.
 *
 * ```
 * val response = snsClient.createTopic("topicName", mapOf("key" to "value")) {
 *  displayName = "displayName"
 * }
 * ```
 *
 * @param topicName Topic name.
 * @param attributes Topic attributes.
 * @param builder Lambda for building [CreateTopicRequest].
 */
suspend inline fun SnsClient.createTopic(
    topicName: String,
    attributes: Map<String, String>? = null,
    crossinline builder: CreateTopicRequest.Builder.() -> Unit = {},
): CreateTopicResponse {
    topicName.requireNotBlank("topicName")

    return createTopic {
        this.name = topicName
        this.attributes = attributes

        builder()
    }
}

/**
 * Creates a FIFO topic. The topic name must end with `.fifo`.
 *
 * ```
 * val response = snsClient.createFifoTopic("topicName.fifo", mapOf("key" to "value")) {
 *     displayName = "displayName"
 * }
 * ```
 *
 * @param topicName Topic name.
 * @param attributes Topic attributes.
 * @param builder Lambda for building [CreateTopicRequest].
 */
suspend inline fun SnsClient.createFifoTopic(
    topicName: String,
    attributes: MutableMap<String, String> = mutableMapOf(),
    crossinline builder: CreateTopicRequest.Builder.() -> Unit = {},
): CreateTopicResponse {
    topicName.requireNotBlank("topicName")
    require(topicName.endsWithIgnoreCase(".fifo")) { "FIFO topic name must end with .fifo" }

    attributes["FifoTopic"] = "true"
    attributes["ContentBasedDeduplication"] = "true"

    return createTopic {
        this.name = topicName
        this.attributes = attributes

        builder()
    }
}

/**
 * Subscribes an endpoint to a topic.
 *
 * ```
 * val response = snsClient.subscribe(topicArn, endpoint, "sms") {
 *  returnSubscriptionArn = true
 * }
 * ```
 *
 * @param topicArn topic ARN
 * @param endpoint endpoint
 * @param protocol Subscription protocol.
 * @param returnSubscriptionArn Whether to return the subscription ARN.
 * @param builder Lambda for building [SubscribeRequest].
 */
suspend inline fun SnsClient.subscribe(
    topicArn: String,
    endpoint: String,
    protocol: String = "sms",
    returnSubscriptionArn: Boolean = true,
    crossinline builder: SubscribeRequest.Builder.() -> Unit = {},
): SubscribeResponse {
    topicArn.requireNotBlank("topicArn")
    endpoint.requireNotBlank("endpoint")

    return subscribe {
        this.topicArn = topicArn
        this.endpoint = endpoint
        this.protocol = protocol
        this.returnSubscriptionArn = returnSubscriptionArn

        builder()
    }
}

/**
 * Checks whether [phoneNumber] has opted out.
 *
 * ```
 * val response = snsClient.checkIfPhoneNumberIsOptedOut(phoneNumber)
 * ```
 *
 * @param phoneNumber Phone number to check.
 * @param builder Lambda for building [CheckIfPhoneNumberIsOptedOutRequest].
 * @return A [CheckIfPhoneNumberIsOptedOutResponse] instance.
 */
suspend inline fun SnsClient.checkIfPhoneNumberIsOptedOut(
    phoneNumber: String,
    crossinline builder: CheckIfPhoneNumberIsOptedOutRequest.Builder.() -> Unit = {},
): CheckIfPhoneNumberIsOptedOutResponse {
    phoneNumber.requireNotBlank("phoneNumber")

    return checkIfPhoneNumberIsOptedOut {
        this.phoneNumber = phoneNumber
        builder()
    }
}

/**
 * Publishes a message to a topic.
 *
 * ```
 * val response = snsClient.publish(topicArn, message, "subject") {
 *      messageAttributes = mapOf("key" to "value")
 * }
 * ```
 *
 * @param topicArn topic ARN
 * @param message Message to publish.
 * @param subject Message subject.
 * @param builder Lambda for building [PublishRequest].
 *
 * @return A [PublishResponse] instance.
 */
suspend inline fun SnsClient.publish(
    topicArn: String,
    message: String,
    subject: String? = null,
    crossinline builder: PublishRequest.Builder.() -> Unit = {},
): PublishResponse {
    topicArn.requireNotBlank("topicArn")
    message.requireNotBlank("message")

    return publish {
        this.topicArn = topicArn
        this.message = message
        subject?.let { this.subject = it }

        builder()
    }
}

/**
 * Publishes multiple messages to a topic in a batch.
 *
 * ```
 * val messageSize = 10
 *
 * // Create the messages to publish.
 * val entries = List(messageSize) {
 *     publishBatchRequestEntryOf(
 *         id = Base58.randomString(6).lowercase(),
 *         message = "Hello, AWS SNS! ${Base58.randomString(6).lowercase()}",
 *         messageDeduplicationId = hashOf(testTopicArn, "Hello, AWS SNS!", testPhoneNumber).toString(),
 *         messageGroupId = "partitionKey"
 *     )
 * }
 *
 * val response = snsClient.publishBatch(testTopicArn, entries) {
 *   messageGroupId = "partitionKey"
 *   messageDeduplicationId = hashOf(topicArn, message, phoneNumber).toString()
 * }
 * ```
 *
 * @param topicArn topic ARN
 * @param entries Messages to publish.
 * @param builder Lambda for building [PublishBatchRequest].
 *
 * @return A [PublishBatchResponse] instance.
 */
suspend inline fun SnsClient.publishBatch(
    topicArn: String,
    entries: List<PublishBatchRequestEntry>,
    crossinline builder: PublishBatchRequest.Builder.() -> Unit = {},
): PublishBatchResponse {
    topicArn.requireNotBlank("topicArn")

    return publishBatch {
        this.topicArn = topicArn
        this.publishBatchRequestEntries = entries
        builder()
    }
}


/**
 * Unsubscribes from a topic.
 *
 * ```
 * val response = snsClient.unsubscribe(subscriptionArn)
 * ```
 *
 * @param subscriptionArn Subscription ARN.
 * @param builder Lambda for building [UnsubscribeRequest].
 * @return An [UnsubscribeResponse] instance.
 */
suspend inline fun SnsClient.unsubscribe(
    subscriptionArn: String,
    crossinline builder: UnsubscribeRequest.Builder.() -> Unit = {},
): UnsubscribeResponse {
    subscriptionArn.requireNotBlank("subscriptionArn")

    return unsubscribe {
        this.subscriptionArn = subscriptionArn
        builder()
    }
}

/**
 * Deletes a topic.
 *
 * ```
 * val response = snsClient.deleteTopic(topicArn)
 * ```
 *
 * @param topicArn topic ARN
 * @param builder Lambda for building [DeleteTopicRequest].
 * @return A [DeleteTopicResponse] instance.
 */
suspend inline fun SnsClient.deleteTopic(
    topicArn: String,
    crossinline builder: DeleteTopicRequest.Builder.() -> Unit = {},
): DeleteTopicResponse {
    topicArn.requireNotBlank("topicArn")

    return deleteTopic {
        this.topicArn = topicArn
        builder()
    }
}
