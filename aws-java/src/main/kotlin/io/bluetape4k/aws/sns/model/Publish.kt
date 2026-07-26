package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest

/**
 * Builds a [PublishRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `topicArn`, `message`, and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = publishRequest {
 *     topicArn("arn:aws:sns:ap-northeast-2:123456:my-topic")
 *     message("Hello SNS")
 * }
 * ```
 */
inline fun publishRequest(
    builder: PublishRequest.Builder.() -> Unit,
): PublishRequest =
    PublishRequest.builder().apply(builder).build()

/**
 * Creates a [PublishRequest] from a topic ARN and message.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [topicArn] is blank.
 * - Throws `IllegalArgumentException` when [message] is blank.
 * - When [snsAttributes] is not null, sets it as message attributes.
 *
 * ```kotlin
 * val req = publishRequestOf(
 *     topicArn = "arn:aws:sns:ap-northeast-2:123456:my-topic",
 *     message = "Hello SNS"
 * )
 * // req.topicArn().isNotBlank() == true
 * ```
 */
inline fun publishRequestOf(
    topicArn: String,
    message: String,
    snsAttributes: Map<String, MessageAttributeValue>? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PublishRequest.Builder.() -> Unit = {},
): PublishRequest {
    topicArn.requireNotBlank("topicArn")
    message.requireNotBlank("message")

    return publishRequest {
        topicArn(topicArn)
        message(message)
        snsAttributes?.let { messageAttributes(it) }
        overrideConfiguration?.let { overrideConfiguration(it) }

        builder()
    }
}
