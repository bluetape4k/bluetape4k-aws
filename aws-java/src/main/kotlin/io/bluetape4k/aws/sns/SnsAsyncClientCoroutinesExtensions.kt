package io.bluetape4k.aws.sns

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointResponse
import software.amazon.awssdk.services.sns.model.CreateTopicResponse

/**
 * Creates an SNS platform endpoint from a device token and platform ARN with coroutines.
 *
 * ## Behavior/Contract
 * - Internally calls [createPlatformEndpointAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = snsAsyncClient.createPlatformEndpoint(
 *     token = "device-token-xyz",
 *     platformApplicationArn = "arn:aws:sns:ap-northeast-2:123456:app/GCM/my-app"
 * )
 * // response.endpointArn().isNotBlank() == true
 * ```
 */
suspend fun SnsAsyncClient.createPlatformEndpoint(
    token: String,
    platformApplicationArn: String,
): CreatePlatformEndpointResponse =
    createPlatformEndpointAsync(token, platformApplicationArn).await()

/**
 * Creates an SNS topic by topic name with coroutines.
 *
 * ## Behavior/Contract
 * - Internally calls [createTopicAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = snsAsyncClient.createTopic("my-topic")
 * // response.topicArn().isNotBlank() == true
 * ```
 */
suspend fun SnsAsyncClient.createTopic(
    topicName: String,
    attributes: Map<String, String> = emptyMap(),
): CreateTopicResponse =
    createTopicAsync(topicName, attributes).await()

/**
 * Creates a FIFO SNS topic with coroutines.
 *
 * ## Behavior/Contract
 * - Internally calls [createFIFOTopicAsync] and waits for completion with `await()`.
 * - The default attributes include `FifoTopic=true` and `ContentBasedDeduplication=true`.
 *
 * ```kotlin
 * val response = snsAsyncClient.createFIFOTopic("my-topic.fifo")
 * // response.topicArn().contains(".fifo") == true
 * ```
 */
suspend fun SnsAsyncClient.createFIFOTopic(
    topicName: String,
    attributes: Map<String, String> = mapOf("FifoTopic" to "true", "ContentBasedDeduplication" to "true"),
): CreateTopicResponse =
    createFIFOTopicAsync(topicName, attributes).await()
