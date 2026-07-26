package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.Tag
import software.amazon.awssdk.services.sns.model.TagResourceRequest

/**
 * Builds a [TagResourceRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `resourceArn`, `tags`, and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = tagResourceRequest {
 *     resourceArn("arn:aws:sns:ap-northeast-2:123456:my-topic")
 *     tags(listOf(Tag.builder().key("env").value("prod").build()))
 * }
 * ```
 */
inline fun tagResourceRequest(
    builder: TagResourceRequest.Builder.() -> Unit,
): TagResourceRequest =
    TagResourceRequest.builder().apply(builder).build()

/**
 * Creates a [TagResourceRequest] from a resource ARN and tag list.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [resourceArn] is blank.
 *
 * ```kotlin
 * val tag = Tag.builder().key("env").value("prod").build()
 * val req = tagResourceRequestOf(
 *     resourceArn = "arn:aws:sns:ap-northeast-2:123456:my-topic",
 *     tags = listOf(tag)
 * )
 * // req.resourceArn().isNotBlank() == true
 * ```
 */
inline fun tagResourceRequestOf(
    resourceArn: String,
    tags: Collection<Tag>,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: TagResourceRequest.Builder.() -> Unit = {},
): TagResourceRequest {
    resourceArn.requireNotBlank("resourceArn")

    return tagResourceRequest {
        resourceArn(resourceArn)
        tags(tags)
        overrideConfiguration?.let { overrideConfiguration(it) }

        builder()
    }
}
