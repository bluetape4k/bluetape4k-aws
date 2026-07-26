package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.ListTagsOfResourceRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [ListTagsOfResourceRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [resourceArn] is blank.
 * - When [nextToken] is set, pagination starts after that token.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = listTagsOfResourceRequestOf("arn:aws:dynamodb:us-east-1:123456789012:table/users")
 * // req.resourceArn == "arn:aws:dynamodb:us-east-1:123456789012:table/users"
 * // req.nextToken == null
 * ```
 *
 * @param resourceArn ARN of the resource whose tags will be listed. Blank values throw.
 * @param nextToken pagination token.
 */
inline fun listTagsOfResourceRequestOf(
    resourceArn: String,
    nextToken: String? = null,
    crossinline builder: ListTagsOfResourceRequest.Builder.() -> Unit = {},
): ListTagsOfResourceRequest {
    resourceArn.requireNotBlank("resourceArn")

    return ListTagsOfResourceRequest {
        this.resourceArn = resourceArn
        this.nextToken = nextToken

        builder()
    }
}
