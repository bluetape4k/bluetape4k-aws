package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.Get
import aws.sdk.kotlin.services.dynamodb.model.KeysAndAttributes
import aws.sdk.kotlin.services.dynamodb.model.ReturnConsumedCapacity
import aws.sdk.kotlin.services.dynamodb.model.TransactGetItem
import aws.sdk.kotlin.services.dynamodb.model.TransactGetItemsRequest
import io.bluetape4k.support.requireNotEmpty

/**
 * Creates a DynamoDB [TransactGetItem] from a [Get] object.
 *
 * ## Behavior and contract
 * - Sets [get] directly on the `get` field of [TransactGetItem].
 *
 * ```kotlin
 * val item = transactGetItemOf(getOf("users", mapOf("id" to AttributeValue.S("u1"))))
 * // item.get?.tableName == "users"
 * ```
 *
 * @param get [Get] object that defines the read operation.
 */
fun transactGetItemOf(get: Get): TransactGetItem =
    TransactGetItem {
        this.get = get
    }

/**
 * Creates a DynamoDB [TransactGetItem] from a table name and key.
 *
 * ## Behavior and contract
 * - Calls [getOf] internally, creates a [Get], then wraps it as [TransactGetItem].
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 *
 * ```kotlin
 * val item = transactGetItemOf("users", emptyMap()) {
 *     projectionExpression = "id, name"
 * }
 * // item.get?.tableName == "users"
 * ```
 *
 * @param tableName DynamoDB table name to read from. Blank values throw.
 * @param key key and attribute map for the item to read.
 * @param expressionAttributeNames projection expression attribute name substitution map.
 * @param projectionExpression projection expression that selects returned attributes.
 */
inline fun transactGetItemOf(
    tableName: String,
    key: Map<String, KeysAndAttributes> = emptyMap(),
    expressionAttributeNames: Map<String, String>? = null,
    projectionExpression: String? = null,
    crossinline builder: Get.Builder.() -> Unit,
): TransactGetItem =
    transactGetItemOf(getOf(tableName, key, expressionAttributeNames, projectionExpression, builder))

/**
 * Builds a DynamoDB [TransactGetItemsRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [transactItems] is empty.
 * - Does not return consumed capacity details when [returnConsumedCapacity] is null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = transactGetItemsRequestOf(
 *     transactItems = listOf(transactGetItemOf(getOf("users", mapOf("id" to AttributeValue.S("u1")))))
 * )
 * // req.transactItems?.size == 1
 * ```
 *
 * @param transactItems [TransactGetItem] list to read transactionally. Empty values throw.
 * @param returnConsumedCapacity whether to return consumed capacity details.
 */
inline fun transactGetItemsRequestOf(
    transactItems: List<TransactGetItem>,
    returnConsumedCapacity: ReturnConsumedCapacity? = null,
    crossinline builder: TransactGetItemsRequest.Builder.() -> Unit = {},
): TransactGetItemsRequest {
    transactItems.requireNotEmpty("transactItems")

    return TransactGetItemsRequest {
        this.transactItems = transactItems
        this.returnConsumedCapacity = returnConsumedCapacity

        builder()
    }
}
