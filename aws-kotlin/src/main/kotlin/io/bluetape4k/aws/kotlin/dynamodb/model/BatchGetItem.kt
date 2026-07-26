package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.BatchGetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.KeysAndAttributes
import aws.sdk.kotlin.services.dynamodb.model.ReturnConsumedCapacity
import io.bluetape4k.support.requireNotEmpty

/**
 * Builds a DynamoDB [BatchGetItemRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [requestItems] is empty.
 * - Uses [KeysAndAttributes] to specify the key set to fetch for each table.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = batchGetItemRequestOf(
 *     requestItems = mapOf("users" to keysAndAttributesOf(listOf(mapOf("id" to AttributeValue.S("u1")))))
 * )
 * // req.requestItems?.size == 1
 * ```
 *
 * @param requestItems mapping from table name to key sets to read. Empty values throw.
 * @param returnConsumedCapacity whether to return consumed capacity details.
 */
inline fun batchGetItemRequestOf(
    requestItems: Map<String, KeysAndAttributes>,
    returnConsumedCapacity: ReturnConsumedCapacity? = null,
    crossinline builder: BatchGetItemRequest.Builder.() -> Unit = {},
): BatchGetItemRequest {
    requestItems.requireNotEmpty("requestItems")

    return BatchGetItemRequest {
        this.requestItems = requestItems
        this.returnConsumedCapacity = returnConsumedCapacity

        builder()
    }
}
