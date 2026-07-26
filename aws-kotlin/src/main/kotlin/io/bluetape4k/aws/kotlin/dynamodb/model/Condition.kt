package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ComparisonOperator
import aws.sdk.kotlin.services.dynamodb.model.Condition

/**
 * Builds a DynamoDB [Condition] with a DSL block. [AttributeValue] list overload.
 *
 * ## Behavior and contract
 * - [comparisonOperator] and [attributeValueList] are required parameters and are always set.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val cond = conditionOf(ComparisonOperator.Eq, listOf(AttributeValue.S("active")))
 * // cond.comparisonOperator == ComparisonOperator.Eq
 * // cond.attributeValueList?.size == 1
 * ```
 *
 * @param comparisonOperator comparison operator.
 * @param attributeValueList [AttributeValue] list used for comparison.
 */
@JvmName("conditionOfAttributeValue")
inline fun conditionOf(
    comparisonOperator: ComparisonOperator,
    attributeValueList: List<AttributeValue>,
    crossinline builder: Condition.Builder.() -> Unit = {},
): Condition = Condition {
    this.comparisonOperator = comparisonOperator
    this.attributeValueList = attributeValueList

    builder()
}

/**
 * Builds a DynamoDB [Condition] with a DSL block. Any? list overload.
 *
 * ## Behavior and contract
 * - Each element in [attributeValueList] is converted into [AttributeValue] through [toAttributeValue].
 * - [comparisonOperator] and the converted [attributeValueList] are always set.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val cond = conditionOf(ComparisonOperator.Eq, listOf("active"))
 * // cond.attributeValueList?.first() == AttributeValue.S("active")
 * ```
 *
 * @param comparisonOperator comparison operator.
 * @param attributeValueList values used for comparison. Converted to [AttributeValue] automatically.
 */
@JvmName("conditionOfAny")
inline fun conditionOf(
    comparisonOperator: ComparisonOperator,
    attributeValueList: List<Any?>,
    crossinline builder: Condition.Builder.() -> Unit = {},
): Condition = Condition {
    this.comparisonOperator = comparisonOperator
    this.attributeValueList = attributeValueList.map { it.toAttributeValue() }

    builder()
}
