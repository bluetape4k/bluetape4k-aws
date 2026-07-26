package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.Update
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

/**
 * Builds a DynamoDB [Update] with a DSL block. [AttributeValue] key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Throws `IllegalArgumentException` when [key] is empty.
 * - Throws `IllegalArgumentException` when [updateExpression] is blank.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val update = updateOf(
 *     tableName = "users",
 *     key = mapOf("id" to AttributeValue.S("u1")),
 *     updateExpression = "SET #n = :name",
 *     expressionAttributeValues = mapOf(":name" to AttributeValue.S("Alice")),
 *     expressionAttributeNames = mapOf("#n" to "name")
 * )
 * // update.tableName == "users"
 * // update.updateExpression == "SET #n = :name"
 * ```
 *
 * @param tableName DynamoDB table name to update. Blank values throw.
 * @param key primary key map for the item to update. Empty values throw.
 * @param updateExpression update expression. Blank values throw.
 * @param expressionAttributeValues update expression value substitution map.
 * @param expressionAttributeNames update expression name substitution map.
 * @param conditionExpression update condition expression.
 */
@JvmName("updateOfAttributeValue")
inline fun updateOf(
    tableName: String,
    key: Map<String, AttributeValue>,
    updateExpression: String,
    expressionAttributeValues: Map<String, AttributeValue>,
    expressionAttributeNames: Map<String, String>? = null,
    conditionExpression: String? = null,
    crossinline builder: Update.Builder.() -> Unit = {},
): Update {
    tableName.requireNotBlank("tableName")
    key.requireNotEmpty("key")
    updateExpression.requireNotBlank("updateExpression")

    return Update {
        this.tableName = tableName
        this.key = key
        this.updateExpression = updateExpression
        this.expressionAttributeValues = expressionAttributeValues

        this.expressionAttributeNames = expressionAttributeNames
        this.conditionExpression = conditionExpression

        builder()
    }
}

/**
 * Builds a DynamoDB [Update] with a DSL block. Any? key overload.
 *
 * ## Behavior and contract
 * - Each value in [key] is converted into [AttributeValue] through [toAttributeValueMap].
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Throws `IllegalArgumentException` when [updateExpression] is blank.
 *
 * ```kotlin
 * val update = updateOf(
 *     tableName = "users",
 *     key = mapOf("id" to "u1"),
 *     updateExpression = "SET #n = :name",
 *     expressionAttributeValues = mapOf(":name" to AttributeValue.S("Alice"))
 * )
 * // update.key?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param tableName DynamoDB table name to update. Blank values throw.
 * @param key primary key map for the item to update. Converted to [AttributeValue] automatically.
 * @param updateExpression update expression. Blank values throw.
 * @param expressionAttributeValues update expression value substitution map.
 * @param expressionAttributeNames update expression name substitution map.
 * @param conditionExpression update condition expression.
 */
@JvmName("updateOfAny")
inline fun updateOf(
    tableName: String,
    key: Map<String, Any?>,
    updateExpression: String,
    expressionAttributeValues: Map<String, AttributeValue>,
    expressionAttributeNames: Map<String, String>? = null,
    conditionExpression: String? = null,
    crossinline builder: Update.Builder.() -> Unit = {},
): Update {
    return updateOf(
        tableName,
        key.toAttributeValueMap(),
        updateExpression,
        expressionAttributeValues,
        expressionAttributeNames,
        conditionExpression,
        builder
    )
}
