package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.UntagResourceRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [UntagResourceRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [resourceArn] is blank.
 * - [tagKeys] is the list of tag keys to remove; an empty list removes no tags.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = untagResourceRequestOf(
 *     resourceArn = "arn:aws:dynamodb:us-east-1:123456789012:table/users",
 *     tagKeys = listOf("env", "team")
 * )
 * // req.tagKeys == listOf("env", "team")
 * ```
 *
 * @param resourceArn ARN of the resource to untag. Blank values throw.
 * @param tagKeys tag keys to remove.
 */
inline fun untagResourceRequestOf(
    resourceArn: String,
    tagKeys: List<String>,
    crossinline builder: UntagResourceRequest.Builder.() -> Unit = {},
): UntagResourceRequest {
    resourceArn.requireNotBlank("resourceArn")

    return UntagResourceRequest {
        this.resourceArn = resourceArn
        this.tagKeys = tagKeys
        builder()
    }
}
