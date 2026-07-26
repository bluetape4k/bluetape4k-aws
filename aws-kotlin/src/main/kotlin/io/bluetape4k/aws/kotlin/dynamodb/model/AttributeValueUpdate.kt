package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeAction
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.AttributeValueUpdate

/**
 * Builds a DynamoDB [AttributeValueUpdate] with a DSL block.
 *
 * ## Behavior and contract
 * - [value] is converted into [AttributeValue] through [toAttributeValue].
 * - [action] selects one of PUT, DELETE, or ADD.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val update = attributeValueUpdateOf("newName", AttributeAction.Put)
 * // update.value == AttributeValue.S("newName")
 * // update.action == AttributeAction.Put
 * ```
 *
 * @param value value to update. Converted to [AttributeValue] automatically.
 * @param action attribute update action to perform.
 */
inline fun <T> attributeValueUpdateOf(
    value: T,
    action: AttributeAction,
    crossinline builder: AttributeValueUpdate.Builder.() -> Unit = {},
): AttributeValueUpdate =
    attributeValueUpdateOf(value.toAttributeValue(), action, builder)

/**
 * Builds a DynamoDB [AttributeValueUpdate] with a DSL block.
 *
 * ## Behavior and contract
 * - [value] is accepted as [AttributeValue] and set without conversion.
 * - [action] selects one of PUT, DELETE, or ADD.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val update = attributeValueUpdateOf(AttributeValue.S("hello"), AttributeAction.Put)
 * // update.value == AttributeValue.S("hello")
 * // update.action == AttributeAction.Put
 * ```
 *
 * @param value [AttributeValue] to update.
 * @param action attribute update action to perform.
 */
inline fun attributeValueUpdateOf(
    value: AttributeValue,
    action: AttributeAction,
    crossinline builder: AttributeValueUpdate.Builder.() -> Unit = {},
): AttributeValueUpdate = AttributeValueUpdate {
    this.value = value
    this.action = action

    builder()
}
