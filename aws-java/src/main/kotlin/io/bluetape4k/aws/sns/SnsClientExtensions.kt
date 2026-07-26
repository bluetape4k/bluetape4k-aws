package io.bluetape4k.aws.sns

import io.bluetape4k.aws.sns.model.createPlatformEndpointRequest
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointResponse
import software.amazon.awssdk.services.sns.model.CreateTopicResponse


/**
 * Creates an SNS platform endpoint from a device [token] and platform application ARN.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [token] is blank.
 * - Throws `IllegalArgumentException` when [platformApplicationArn] is blank.
 *
 * ```kotlin
 * val response = snsClient.createPlatformEndpoint(
 *     token = "device-token-xyz",
 *     platformApplicationArn = "arn:aws:sns:ap-northeast-2:123456:app/GCM/my-app"
 * )
 * // response.endpointArn().isNotBlank() == true
 * ```
 */
fun SnsClient.createPlatformEndpoint(token: String, platformApplicationArn: String): CreatePlatformEndpointResponse {
    token.requireNotBlank("token")
    platformApplicationArn.requireNotBlank("platformApplicationArn")

    val request = createPlatformEndpointRequest {
        token(token)
        platformApplicationArn(platformApplicationArn)
    }
    return createPlatformEndpoint(request)
}

/**
 * Creates an SNS topic with [topicName].
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [topicName] is blank.
 *
 * ```kotlin
 * val response = snsClient.createTopic("my-topic")
 * // response.topicArn().isNotBlank() == true
 * ```
 */
fun SnsClient.createTopic(
    topicName: String,
    attributes: Map<String, String> = emptyMap(),
): CreateTopicResponse {
    topicName.requireNotBlank("topicName")
    return createTopic { it.name(topicName).attributes(attributes) }
}

/**
 * Creates a FIFO SNS topic with [topicName].
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [topicName] is blank.
 * - The default attributes include `FifoTopic=true` and `ContentBasedDeduplication=true`.
 *
 * ```kotlin
 * val response = snsClient.createFIFOTopic("my-topic.fifo")
 * // response.topicArn().contains(".fifo") == true
 * ```
 */
fun SnsClient.createFIFOTopic(
    topicName: String,
    attributes: Map<String, String> = mapOf("FifoTopic" to "true", "ContentBasedDeduplication" to "true"),
): CreateTopicResponse {
    topicName.requireNotBlank("topicName")
    return createTopic { it.name(topicName).attributes(attributes) }
}
