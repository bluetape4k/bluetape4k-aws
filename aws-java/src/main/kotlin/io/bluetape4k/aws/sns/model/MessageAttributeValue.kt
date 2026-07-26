package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sns.model.MessageAttributeValue

/**
 * Builds a [MessageAttributeValue] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `stringValue`, `dataType`, and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val attr = messageAttributeValue {
 *     stringValue("Hello")
 *     dataType("String")
 * }
 * ```
 */
inline fun messageAttributeValue(
    builder: MessageAttributeValue.Builder.() -> Unit,
): MessageAttributeValue =
    MessageAttributeValue.builder().apply(builder).build()

/**
 * Creates a [MessageAttributeValue] from a string value and data type.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [valueAsString] is blank.
 * - The default [dataType] is `"String"`.
 *
 * ```kotlin
 * val attr = messageAttributeValueOf("Hello SNS")
 * // attr.stringValue() == "Hello SNS"
 * // attr.dataType() == "String"
 * ```
 */
inline fun messageAttributeValueOf(
    valueAsString: String,
    dataType: String = "String",
    builder: MessageAttributeValue.Builder.() -> Unit = {},
): MessageAttributeValue {
    valueAsString.requireNotBlank("valueAsString")

    return messageAttributeValue {
        stringValue(valueAsString)
        dataType(dataType)

        builder()
    }
}

/**
 * Converts this [String] to an SNS message attribute [MessageAttributeValue].
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when the receiver string is blank.
 * - The default [dataType] is `"String"`.
 *
 * ```kotlin
 * val attr = "Hello SNS".toMessageAttributeValue()
 * // attr.stringValue() == "Hello SNS"
 * ```
 */
inline fun String.toMessageAttributeValue(
    dataType: String = "String",
    builder: MessageAttributeValue.Builder.() -> Unit = {},
): MessageAttributeValue =
    messageAttributeValueOf(this, dataType, builder)
