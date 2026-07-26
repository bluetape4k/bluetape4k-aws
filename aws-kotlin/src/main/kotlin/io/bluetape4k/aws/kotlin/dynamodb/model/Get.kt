package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.Get
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [Get] with a DSL block. [AttributeValue] key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - [key] is the primary key map for the item to read and is omitted when null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val get = getOf("users", mapOf("id" to AttributeValue.S("u1")))
 * // get.tableName == "users"
 * // get.key?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param tableName DynamoDB table name to read from. Blank values throw.
 * @param key primary key map for the item to read.
 * @param expressionAttributeNames projection expression attribute name substitution map.
 * @param projectionExpression projection expression that selects returned attributes.
 */
@JvmName("getOfAttributeValue")
inline fun getOf(
    tableName: String,
    key: Map<String, AttributeValue>? = null,
    expressionAttributeNames: Map<String, String>? = null,
    projectionExpression: String? = null,
    crossinline builder: Get.Builder.() -> Unit = {},
): Get {
    tableName.requireNotBlank("tableName")

    return Get {
        this.tableName = tableName
        this.key = key
        this.expressionAttributeNames = expressionAttributeNames
        this.projectionExpression = projectionExpression

        builder()
    }
}

/**
 * Builds a DynamoDB [Get] with a DSL block. Any? key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Each value in [key] is converted into [AttributeValue] through [toAttributeValueMap].
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val get = getOf("users", mapOf("id" to "u1"))
 * // get.key?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param tableName DynamoDB table name to read from. Blank values throw.
 * @param key primary key map for the item to read. Converted to [AttributeValue] automatically.
 * @param expressionAttributeNames projection expression attribute name substitution map.
 * @param projectionExpression projection expression that selects returned attributes.
 */
@JvmName("getOfAny")
inline fun getOf(
    tableName: String,
    key: Map<String, Any?>? = null,
    expressionAttributeNames: Map<String, String>? = null,
    projectionExpression: String? = null,
    crossinline builder: Get.Builder.() -> Unit = {},
): Get = getOf(
    tableName,
    key?.toAttributeValueMap(),
    expressionAttributeNames,
    projectionExpression,
    builder,
)
