package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.Tag
import aws.sdk.kotlin.services.dynamodb.model.TagResourceRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [TagResourceRequest] with a DSL block. List overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [resourceArn] is blank.
 * - Creates the request without tags when [tags] is null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = tagResourceRequestOf(
 *     resourceArn = "arn:aws:dynamodb:us-east-1:123456789012:table/users",
 *     tags = listOf(Tag { key = "env"; value = "prod" })
 * )
 * // req.tags?.size == 1
 * ```
 *
 * @param resourceArn ARN of the resource to tag. Blank values throw.
 * @param tags [Tag] list to add.
 */
@JvmName("tagResourceRequestOfTagList")
inline fun tagResourceRequestOf(
    resourceArn: String,
    tags: List<Tag>? = null,
    crossinline builder: TagResourceRequest.Builder.() -> Unit = {},
): TagResourceRequest {
    resourceArn.requireNotBlank("resourceArn")

    return TagResourceRequest {
        this.resourceArn = resourceArn
        this.tags = tags

        builder()
    }
}

/**
 * Builds a DynamoDB [TagResourceRequest] with a DSL block. Vararg overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [resourceArn] is blank.
 * - Converts the [tags] vararg to a list and delegates to the list overload.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = tagResourceRequestOf(
 *     resourceArn = "arn:aws:dynamodb:us-east-1:123456789012:table/users",
 *     Tag { key = "env"; value = "prod" },
 *     Tag { key = "team"; value = "backend" }
 * )
 * // req.tags?.size == 2
 * ```
 *
 * @param resourceArn ARN of the resource to tag. Blank values throw.
 * @param tags [Tag] values to add.
 */
@JvmName("tagResourceRequestOfTagArray")
inline fun tagResourceRequestOf(
    resourceArn: String,
    vararg tags: Tag,
    crossinline builder: TagResourceRequest.Builder.() -> Unit = {},
): TagResourceRequest {
    resourceArn.requireNotBlank("resourceArn")

    return tagResourceRequestOf(
        resourceArn,
        tags.toList(),
        builder
    )
}
