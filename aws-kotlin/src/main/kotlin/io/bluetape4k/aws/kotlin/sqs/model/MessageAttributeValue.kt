package io.bluetape4k.aws.kotlin.sqs.model

import aws.sdk.kotlin.services.sqs.model.MessageAttributeValue

/**
 * Creates an SQS [MessageAttributeValue] from a string value.
 *
 * ```kotlin
 * val attr = messageAttributeValueOf("hello")
 * // attr.stringValue == "hello"
 * ```
 *
 * @param value String value; null is allowed.
 * @param builder Lambda for configuring [MessageAttributeValue.Builder].
 * @return A [MessageAttributeValue] instance.
 */
@JvmName("messageAttributeValueOfNullableString")
inline fun messageAttributeValueOf(
    value: String?,
    crossinline builder: MessageAttributeValue.Builder.() -> Unit = {},
): MessageAttributeValue =
    MessageAttributeValue {
        stringValue = value
        dataType = "String"
        builder()
    }

/**
 * Creates an SQS [MessageAttributeValue] from a list of strings.
 *
 * ```kotlin
 * val attr = messageAttributeValueOf(listOf("a", "b", "c"))
 * // attr.stringListValues == ["a", "b", "c"]
 * ```
 *
 * @param values String values; null is allowed.
 * @param builder Lambda for configuring [MessageAttributeValue.Builder].
 * @return A [MessageAttributeValue] instance.
 */
@JvmName("messageAttributeValueOfNullableStringList")
inline fun messageAttributeValueOf(
    values: List<String>?,
    crossinline builder: MessageAttributeValue.Builder.() -> Unit = {},
): MessageAttributeValue =
    MessageAttributeValue {
        stringListValues = values
        dataType = "String"
        builder()
    }

/**
 * Creates an SQS [MessageAttributeValue] from a binary value.
 *
 * ```kotlin
 * val attr = messageAttributeValueOf(byteArrayOf(1, 2, 3))
 * // attr.binaryValue?.contentEquals(byteArrayOf(1, 2, 3)) == true
 * ```
 *
 * @param value Binary value; null is allowed.
 * @param builder Lambda for configuring [MessageAttributeValue.Builder].
 * @return A [MessageAttributeValue] instance.
 */
@JvmName("messageAttributeValueOfNullableByteArray")
inline fun messageAttributeValueOf(
    value: ByteArray?,
    crossinline builder: MessageAttributeValue.Builder.() -> Unit = {},
): MessageAttributeValue =
    MessageAttributeValue {
        binaryValue = value
        dataType = "Binary"
        builder()
    }

/**
 * Creates an SQS [MessageAttributeValue] from a list of binary values.
 *
 * ```kotlin
 * val attr = messageAttributeValueOf(listOf(byteArrayOf(1), byteArrayOf(2)))
 * // attr.binaryListValues?.size == 2
 * ```
 *
 * @param values Binary values; null is allowed.
 * @param builder Lambda for configuring [MessageAttributeValue.Builder].
 * @return A [MessageAttributeValue] instance.
 */
@JvmName("messageAttributeValueOfNullableByteArrayList")
inline fun messageAttributeValueOf(
    values: List<ByteArray>?,
    crossinline builder: MessageAttributeValue.Builder.() -> Unit = {},
): MessageAttributeValue =
    MessageAttributeValue {
        binaryListValues = values
        dataType = "Binary"
        builder()
    }
