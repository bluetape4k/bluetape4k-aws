package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.SubscribeRequest

/**
 * Builds a [SubscribeRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `topicArn`, `protocol`, `endpoint`, and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = subscribeRequest {
 *     topicArn("arn:aws:sns:ap-northeast-2:123456:my-topic")
 *     protocol("email")
 *     endpoint("user@example.com")
 * }
 * ```
 */
inline fun subscribeRequest(
    builder: SubscribeRequest.Builder.() -> Unit,
): SubscribeRequest =
    SubscribeRequest.builder().apply(builder).build()

/**
 * Creates a [SubscribeRequest] from a topic ARN, protocol, and endpoint.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [topicArn] is blank.
 * - Throws `IllegalArgumentException` when [protocol] is blank.
 * - Throws `IllegalArgumentException` when [endpoint] is blank.
 *
 * ```kotlin
 * val req = subscribeRequestOf(
 *     topicArn = "arn:aws:sns:ap-northeast-2:123456:my-topic",
 *     protocol = "sqs",
 *     endpoint = "arn:aws:sqs:ap-northeast-2:123456:my-queue"
 * )
 * // req.topicArn().isNotBlank() == true
 * ```
 */
inline fun subscribeRequestOf(
    topicArn: String,
    protocol: String,
    endpoint: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: SubscribeRequest.Builder.() -> Unit = {},
): SubscribeRequest {
    topicArn.requireNotBlank("topicArn")
    protocol.requireNotBlank("protocol")
    endpoint.requireNotBlank("endpoint")

    return subscribeRequest {
        topicArn(topicArn)
        protocol(protocol)
        endpoint(endpoint)
        overrideConfiguration?.let { overrideConfiguration(it) }

        builder()
    }
}
