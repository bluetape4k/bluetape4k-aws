package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest

/**
 * Builds a [DeleteTopicRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `topicArn` and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = deleteTopicRequest {
 *     topicArn("arn:aws:sns:ap-northeast-2:123456:my-topic")
 * }
 * ```
 */
inline fun deleteTopicRequest(
    builder: DeleteTopicRequest.Builder.() -> Unit,
): DeleteTopicRequest =
    DeleteTopicRequest.builder().apply(builder).build()

/**
 * Creates a [DeleteTopicRequest] from a topic ARN.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [topicArn] is blank.
 *
 * ```kotlin
 * val req = deleteTopicRequestOf("arn:aws:sns:ap-northeast-2:123456:my-topic")
 * // req.topicArn().isNotBlank() == true
 * ```
 */
inline fun deleteTopicRequestOf(
    topicArn: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: DeleteTopicRequest.Builder.() -> Unit = {},
): DeleteTopicRequest {
    topicArn.requireNotBlank("topicArn")

    return deleteTopicRequest {
        topicArn(topicArn)
        overrideConfiguration?.let { overrideConfiguration(it) }
        builder()
    }
}
