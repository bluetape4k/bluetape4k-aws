package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionCheck

/**
 * Builds a DynamoDB [ConditionCheck] with a DSL block. [AttributeValue] map overload.
 *
 * ## Behavior and contract
 * - [conditionExpression] is the condition expression string and is omitted when null.
 * - [key] and [expressionAttributeValues] accept [AttributeValue] maps directly.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val check = conditionCheckOf(
 *     conditionExpression = "attribute_exists(id)",
 *     key = mapOf("id" to AttributeValue.S("u1"))
 * )
 * // check.conditionExpression == "attribute_exists(id)"
 * ```
 *
 * @param conditionExpression condition expression string.
 * @param expressionAttributeNames expression attribute name substitution map.
 * @param expressionAttributeValues expression attribute value substitution map.
 * @param key primary key map for the item to check.
 */
@JvmName("conditionCheckOfAttributeValue")
inline fun conditionCheckOf(
    conditionExpression: String? = null,
    expressionAttributeNames: Map<String, String>? = null,
    expressionAttributeValues: Map<String, AttributeValue>? = null,
    key: Map<String, AttributeValue>? = null,
    crossinline builder: ConditionCheck.Builder.() -> Unit = {},
): ConditionCheck = ConditionCheck {
    this.conditionExpression = conditionExpression
    this.expressionAttributeNames = expressionAttributeNames
    this.expressionAttributeValues = expressionAttributeValues
    this.key = key

    builder()
}

/**
 * Builds a DynamoDB [ConditionCheck] with a DSL block. Any? map overload.
 *
 * ## Behavior and contract
 * - Values in [expressionAttributeValues] and [key] are converted into [AttributeValue] through [toAttributeValueMap].
 * - [conditionExpression] is omitted when null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val check = conditionCheckOf(
 *     conditionExpression = "attribute_exists(id)",
 *     key = mapOf("id" to "u1")
 * )
 * // check.key?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param conditionExpression condition expression string.
 * @param expressionAttributeNames expression attribute name substitution map.
 * @param expressionAttributeValues expression attribute value substitution map. Converted to [AttributeValue] automatically.
 * @param key primary key map for the item to check. Converted to [AttributeValue] automatically.
 */
@JvmName("conditionCheckOfAny")
inline fun conditionCheckOf(
    conditionExpression: String? = null,
    expressionAttributeNames: Map<String, String>? = null,
    expressionAttributeValues: Map<String, Any?>? = null,
    key: Map<String, Any?>? = null,
    crossinline builder: ConditionCheck.Builder.() -> Unit = {},
): ConditionCheck = ConditionCheck {
    this.conditionExpression = conditionExpression
    this.expressionAttributeNames = expressionAttributeNames
    this.expressionAttributeValues = expressionAttributeValues?.toAttributeValueMap()
    this.key = key?.toAttributeValueMap()

    builder()
}
