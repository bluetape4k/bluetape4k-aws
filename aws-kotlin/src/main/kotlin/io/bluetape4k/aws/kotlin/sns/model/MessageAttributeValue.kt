package io.bluetape4k.aws.kotlin.sns.model

import aws.sdk.kotlin.services.sns.model.MessageAttributeValue
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [MessageAttributeValue] containing [stringValue].
 *
 * ```
 * val messageAttributeValue = messageAttributeValueOf("stringValue")
 * ```
 *
 * @param stringValue String value.
 * @param builder Lambda for configuring [MessageAttributeValue.Builder].
 * @return A [MessageAttributeValue] instance.
 */
inline fun messageAttributeValueOf(
    stringValue: String,
    crossinline builder: MessageAttributeValue.Builder.() -> Unit = {},
): MessageAttributeValue {
    stringValue.requireNotBlank("stringValue")

    return MessageAttributeValue {
        this.stringValue = stringValue
        this.dataType = "String"
        builder()
    }
}

/**
 * Creates a [MessageAttributeValue] containing [binaryValue].
 *
 * ```
 * val messageAttributeValue = messageAttributeValueOf(byteArrayOf(0x01, 0x02, 0x03))
 * ```
 *
 * @param binaryValue Binary value.
 * @param builder Lambda for configuring [MessageAttributeValue.Builder].
 * @return A [MessageAttributeValue] instance.
 */
inline fun messageAttributeValueOf(
    binaryValue: ByteArray,
    crossinline builder: MessageAttributeValue.Builder.() -> Unit = {},
): MessageAttributeValue {
    require(binaryValue.isNotEmpty()) { "binaryValue must not be empty." }

    return MessageAttributeValue {
        this.binaryValue = binaryValue
        this.dataType = "Binary"
        builder()
    }
}

/**
 * Creates a numeric [MessageAttributeValue] containing [numberValue].
 *
 * ```
 * val messageAttributeValue = messageAttributeValueOf(123)
 * ```
 *
 * @param numberValue Numeric value.
 * @param builder Lambda for configuring [MessageAttributeValue.Builder].
 * @return A [MessageAttributeValue] instance.
 */
inline fun <T: Number> messageAttributeValueOf(
    numberValue: T,
    crossinline builder: MessageAttributeValue.Builder.() -> Unit = {},
): MessageAttributeValue =
    MessageAttributeValue {
        this.stringValue = numberValue.toString()
        this.dataType = "Number"
        builder()
    }
