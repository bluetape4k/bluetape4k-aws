package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.BatchWriteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.ReturnConsumedCapacity
import aws.sdk.kotlin.services.dynamodb.model.WriteRequest
import io.bluetape4k.support.requireNotEmpty

/**
 * Builds a DynamoDB [BatchWriteItemRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [requestItems] is empty.
 * - Uses [WriteRequest] lists to specify Put/Delete requests for each table.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = batchWriteItemRequestOf(
 *     requestItems = mapOf("users" to listOf(writePutRequestOf(mapOf("id" to "u1"))))
 * )
 * // req.requestItems?.size == 1
 * ```
 *
 * @param requestItems mapping from table name to write request list. Empty values throw.
 * @param returnConsumedCapacity whether to return consumed capacity details.
 */
inline fun batchWriteItemRequestOf(
    requestItems: Map<String, List<WriteRequest>>,
    returnConsumedCapacity: ReturnConsumedCapacity? = null,
    crossinline builder: BatchWriteItemRequest.Builder.() -> Unit = {},
): BatchWriteItemRequest {
    requestItems.requireNotEmpty("requestItems")

    return BatchWriteItemRequest {
        this.requestItems = requestItems
        this.returnConsumedCapacity = returnConsumedCapacity

        builder()
    }
}
