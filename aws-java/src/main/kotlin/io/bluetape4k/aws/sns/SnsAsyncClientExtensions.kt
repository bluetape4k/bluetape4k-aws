package io.bluetape4k.aws.sns

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointResponse
import software.amazon.awssdk.services.sns.model.CreateTopicResponse
import java.util.concurrent.CompletableFuture

/**
 * Creates an SNS platform endpoint asynchronously from a device [token] and platform application ARN.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [token] is blank.
 * - Throws `IllegalArgumentException` when [platformApplicationArn] is blank.
 *
 * ```kotlin
 * val response = snsAsyncClient.createPlatformEndpointAsync(
 *     token = "device-token-xyz",
 *     platformApplicationArn = "arn:aws:sns:ap-northeast-2:123456:app/GCM/my-app"
 * ).join()
 * // response.endpointArn().isNotBlank() == true
 * ```
 */
fun SnsAsyncClient.createPlatformEndpointAsync(
    token: String,
    platformApplicationArn: String,
): CompletableFuture<CreatePlatformEndpointResponse> {
    token.requireNotBlank("token")
    platformApplicationArn.requireNotBlank("platformApplicationArn")

    return createPlatformEndpoint {
        it.token(token)
        it.platformApplicationArn(platformApplicationArn)
    }
}

/**
 * Creates an SNS topic asynchronously with [topicName].
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [topicName] is blank.
 *
 * ```kotlin
 * val response = snsAsyncClient.createTopicAsync("my-topic").join()
 * // response.topicArn().isNotBlank() == true
 * ```
 */
fun SnsAsyncClient.createTopicAsync(
    topicName: String,
    attributes: Map<String, String> = emptyMap(),
): CompletableFuture<CreateTopicResponse> {
    topicName.requireNotBlank("topicName")
    return createTopic {
        it.name(topicName)
            .attributes(attributes)
    }
}

/**
 * Creates a FIFO SNS topic asynchronously with [topicName].
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [topicName] is blank.
 * - The default attributes include `FifoTopic=true` and `ContentBasedDeduplication=true`.
 *
 * ```kotlin
 * val response = snsAsyncClient.createFIFOTopicAsync("my-topic.fifo").join()
 * // response.topicArn().contains(".fifo") == true
 * ```
 */
fun SnsAsyncClient.createFIFOTopicAsync(
    topicName: String,
    attributes: Map<String, String> = mapOf("FifoTopic" to "true", "ContentBasedDeduplication" to "true"),
): CompletableFuture<CreateTopicResponse> {
    topicName.requireNotBlank("topicName")

    return createTopic {
        it.name(topicName)
            .attributes(attributes)
    }
}
