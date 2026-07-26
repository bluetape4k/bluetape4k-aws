@file:Suppress("NOTHING_TO_INLINE")

package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.Put
import aws.sdk.kotlin.services.dynamodb.model.PutRequest
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

/**
 * Builds a DynamoDB [Put] with a DSL block. [AttributeValue] item overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Throws `IllegalArgumentException` when [item] is empty.
 * - Additional fields such as condition expressions can be overridden through [builder].
 *
 * ```kotlin
 * val put = putOf("users", mapOf("id" to AttributeValue.S("u1"), "name" to AttributeValue.S("Alice")))
 * // put.tableName == "users"
 * // put.item?.size == 2
 * ```
 *
 * @param tableName DynamoDB table name to store the item in. Blank values throw.
 * @param item attribute map for the item to store. Empty values throw.
 */
@JvmName("putOfAttributeValue")
inline fun putOf(
    tableName: String,
    item: Map<String, AttributeValue>? = null,
    crossinline builder: Put.Builder.() -> Unit = {},
): Put {
    tableName.requireNotBlank("tableName")
    item.requireNotEmpty("item")

    return Put {
        this.tableName = tableName
        this.item = item

        builder()
    }
}

/**
 * Builds a DynamoDB [Put] with a DSL block. Any? item overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Each value in [item] is converted into [AttributeValue] through [toAttributeValueMap].
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val put = putOf("users", mapOf("id" to "u1", "name" to "Alice"))
 * // put.item?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param tableName DynamoDB table name to store the item in. Blank values throw.
 * @param item attribute map for the item to store. Converted to [AttributeValue] automatically.
 */
@JvmName("putOfAny")
inline fun putOf(
    tableName: String,
    item: Map<String, Any?>? = null,
    crossinline builder: Put.Builder.() -> Unit = {},
): Put =
    putOf(tableName, item?.toAttributeValueMap(), builder)

/**
 * Builds a DynamoDB [PutRequest]. [AttributeValue] item overload.
 *
 * ## Behavior and contract
 * - [item] is the attribute map for the item to store and is set directly without conversion.
 *
 * ```kotlin
 * val req = putRequestOf(mapOf("id" to AttributeValue.S("u1")))
 * // req.item?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param item [AttributeValue] attribute map for the item to store.
 */
@JvmName("putRequestOfAttributeValue")
inline fun putRequestOf(
    item: Map<String, AttributeValue>,
): PutRequest = PutRequest {
    this.item = item
}

/**
 * Builds a DynamoDB [PutRequest]. Any? item overload.
 *
 * ## Behavior and contract
 * - Each value in [item] is converted into [AttributeValue] through [toAttributeValueMap].
 *
 * ```kotlin
 * val req = putRequestOf(mapOf("id" to "u1"))
 * // req.item?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param item attribute map for the item to store. Converted to [AttributeValue] automatically.
 */
@JvmName("putRequestOfAny")
inline fun putRequestOf(
    item: Map<String, Any?>,
    crossinline builder: PutRequest.Builder.() -> Unit = {},
): PutRequest =
    putRequestOf(item.toAttributeValueMap())
