package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.GetTopicAttributesRequest

/**
 * Builds a [GetTopicAttributesRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `topicArn` and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = getTopicAttributesRequest {
 *     topicArn("arn:aws:sns:ap-northeast-2:123456:my-topic")
 * }
 * ```
 */
inline fun getTopicAttributesRequest(
    builder: GetTopicAttributesRequest.Builder.() -> Unit,
): GetTopicAttributesRequest =
    GetTopicAttributesRequest.builder().apply(builder).build()

/**
 * Creates a [GetTopicAttributesRequest] from a topic ARN.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [topicArn] is non-null and blank.
 *
 * ```kotlin
 * val req = getTopicAttributesRequestOf("arn:aws:sns:ap-northeast-2:123456:my-topic")
 * // req.topicArn().isNotBlank() == true
 * ```
 */
inline fun getTopicAttributesRequestOf(
    topicArn: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: GetTopicAttributesRequest.Builder.() -> Unit = {},
): GetTopicAttributesRequest =
    getTopicAttributesRequest {
        topicArn?.let {
            topicArn.requireNotBlank("topicArn")
            topicArn(it)
        }
        overrideConfiguration?.let { overrideConfiguration(it) }

        builder()
    }
