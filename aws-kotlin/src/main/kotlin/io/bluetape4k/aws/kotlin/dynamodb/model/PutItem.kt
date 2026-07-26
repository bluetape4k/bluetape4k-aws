package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.ReturnValue
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

/**
 * Builds a DynamoDB [PutItemRequest] with a DSL block. [AttributeValue] item overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Throws `IllegalArgumentException` when [item] is empty.
 * - Additional fields such as condition expressions can be overridden through [builder].
 *
 * ```kotlin
 * val req = putItemRequestOf(
 *     "users",
 *     mapOf("id" to AttributeValue.S("u1"), "name" to AttributeValue.S("Alice"))
 * )
 * // req.tableName == "users"
 * // req.item?.size == 2
 * ```
 *
 * @param tableName DynamoDB table name to store the item in. Blank values throw.
 * @param item attribute map for the item to store. Empty values throw.
 * @param returnValues previous/current value settings to include in the response.
 */
@JvmName("putItemRequestOfAttributeValue")
inline fun putItemRequestOf(
    tableName: String,
    item: Map<String, AttributeValue>,
    returnValues: ReturnValue? = null,
    crossinline builder: PutItemRequest.Builder.() -> Unit = {},
): PutItemRequest {
    tableName.requireNotBlank("tableName")
    item.requireNotEmpty("item")

    return PutItemRequest {
        this.tableName = tableName
        this.item = item
        this.returnValues = returnValues

        builder()
    }
}

/**
 * Builds a DynamoDB [PutItemRequest] with a DSL block. Any? item overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Each value in [item] is converted into [AttributeValue] through [toAttributeValueMap].
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = putItemRequestOf("users", mapOf("id" to "u1", "name" to "Alice"))
 * // req.item?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param tableName DynamoDB table name to store the item in. Blank values throw.
 * @param item attribute map for the item to store. Converted to [AttributeValue] automatically.
 * @param returnValues previous/current value settings to include in the response.
 */
@JvmName("putItemRequestOfAny")
inline fun putItemRequestOf(
    tableName: String,
    item: Map<String, Any?>,
    returnValues: ReturnValue? = null,
    crossinline builder: PutItemRequest.Builder.() -> Unit = {},
): PutItemRequest =
    putItemRequestOf(
        tableName,
        item.toAttributeValueMap(),
        returnValues,
        builder,
    )
