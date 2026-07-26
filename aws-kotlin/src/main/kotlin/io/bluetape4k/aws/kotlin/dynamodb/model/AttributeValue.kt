@file:Suppress("NOTHING_TO_INLINE")

package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import io.bluetape4k.io.getAllBytes
import io.bluetape4k.io.toByteArray
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Converts a Kotlin value into a DynamoDB [AttributeValue].
 *
 * ## Behavior and contract
 * - null -> `AttributeValue.Null(true)`
 * - [ByteArray]/[ByteBuffer] -> `AttributeValue.B`
 * - [String] -> `AttributeValue.S`
 * - [Number] -> `AttributeValue.N` after `toString()`
 * - [Boolean] -> `AttributeValue.Bool`
 * - [Iterable] -> `AttributeValue.L`
 * - [Map] -> `AttributeValue.M`
 * - Other values -> `AttributeValue.S` after `toString()`
 *
 * ```kotlin
 * val s = "hello".toAttributeValue()   // AttributeValue.S("hello")
 * val n = 42.toAttributeValue()        // AttributeValue.N("42")
 * val b = true.toAttributeValue()      // AttributeValue.Bool(true)
 * ```
 */
fun <T> T.toAttributeValue(): AttributeValue = when (this) {
    null              -> AttributeValue.Null(true)
    is AttributeValue -> this
    is ByteArray      -> AttributeValue.B(this)
    is ByteBuffer     -> this.toAttributeValue()
    is String         -> AttributeValue.S(this)
    is Number         -> AttributeValue.N(this.toString())
    is Boolean        -> AttributeValue.Bool(this)

    is Iterable<*>    -> this.toAttributeValue()
    is Map<*, *>      -> this.toAttributeValue()
    else              -> this.toString().toAttributeValue()
}


/**
 * Converts a [ByteArray] into DynamoDB [AttributeValue.B].
 *
 * ```kotlin
 * val av = byteArrayOf(1, 2, 3).toAttributeValue()   // AttributeValue.B(...)
 * ```
 */
inline fun ByteArray.toAttributeValue(): AttributeValue = AttributeValue.B(this)

/**
 * Converts a [ByteBuffer] into DynamoDB [AttributeValue.B].
 *
 * ```kotlin
 * val buf = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
 * val av = buf.toAttributeValue()   // AttributeValue.B(...)
 * ```
 */
inline fun ByteBuffer.toAttributeValue(): AttributeValue = AttributeValue.B(this.getAllBytes())

/**
 * Converts a [String] into DynamoDB [AttributeValue.S].
 *
 * ```kotlin
 * val av = "hello".toAttributeValue()   // AttributeValue.S("hello")
 * ```
 */
inline fun String.toAttributeValue(): AttributeValue = AttributeValue.S(this)

/**
 * Converts a [Number] into DynamoDB [AttributeValue.N].
 *
 * ```kotlin
 * val av = 42.toAttributeValue()   // AttributeValue.N("42")
 * ```
 */
inline fun Number.toAttributeValue(): AttributeValue = AttributeValue.N(this.toString())

/**
 * Converts a [Boolean] into DynamoDB [AttributeValue.Bool].
 *
 * ```kotlin
 * val av = true.toAttributeValue()   // AttributeValue.Bool(true)
 * ```
 */
inline fun Boolean.toAttributeValue(): AttributeValue = AttributeValue.Bool(this)

/**
 * Converts a [ByteArray] collection into DynamoDB [AttributeValue.Bs].
 *
 * ```kotlin
 * val av = listOf(byteArrayOf(1), byteArrayOf(2)).toAttributeValue()   // AttributeValue.Bs(...)
 * ```
 */
@JvmName("toAttributeValueByteArrayList")
inline fun Iterable<ByteArray>.toAttributeValue(): AttributeValue = AttributeValue.Bs(this.toList())

/**
 * Converts a [CharSequence] collection into DynamoDB [AttributeValue.Ss].
 *
 * ```kotlin
 * val av = listOf("a", "b").toAttributeValue()   // AttributeValue.Ss(["a", "b"])
 * ```
 */
@JvmName("toAttributeValueStringList")
inline fun <T: CharSequence> Iterable<T>.toAttributeValue(): AttributeValue =
    AttributeValue.Ss(this.map { it.toString() })

/**
 * Converts a [Number] collection into DynamoDB [AttributeValue.Ns].
 *
 * ```kotlin
 * val av = listOf(1, 2, 3).toAttributeValue()   // AttributeValue.Ns(["1", "2", "3"])
 * ```
 */
@JvmName("toAttributeValueNumberList")
fun <T: Number> Iterable<T>.toAttributeValue(): AttributeValue = AttributeValue.Ns(this.map { it.toString() })

/**
 * Converts an [InputStream] into DynamoDB [AttributeValue.B].
 *
 * ```kotlin
 * val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
 * val av = stream.toAttributeValue()   // AttributeValue.B(...)
 * ```
 */
inline fun InputStream.toAttributeValue(): AttributeValue =
    AttributeValue.B(this.toByteArray())

/**
 * Converts [Iterable] elements into a DynamoDB [AttributeValue.L] list.
 *
 * ```kotlin
 * val av = listOf("a", 1, true).toAttributeValue()   // AttributeValue.L(...)
 * ```
 */
inline fun <T> Iterable<T>.toAttributeValue() = AttributeValue.L(this.map { it.toAttributeValue() })

/**
 * Converts a [Map] into DynamoDB [AttributeValue.M].
 *
 * ```kotlin
 * val av = mapOf("name" to "Alice", "age" to 30).toAttributeValue()
 * // (av as AttributeValue.M).value["name"] == AttributeValue.S("Alice")
 * ```
 */
inline fun <K: Any, V> Map<K, V>.toAttributeValue(): AttributeValue.M =
    AttributeValue.M(this.entries.associate { it.key.toString() to it.value.toAttributeValue() })


/**
 * Converts this iterable's elements into a list of [AttributeValue] values.
 *
 * ## Behavior and contract
 * - Returns a new list by applying [toAttributeValue] to each element.
 *
 * ```kotlin
 * val list = listOf("a", "b").toAttributeValueList()
 * // list == [AttributeValue.S("a"), AttributeValue.S("b")]
 * ```
 */
inline fun <T> Iterable<T>.toAttributeValueList(): List<AttributeValue> = this.map { it.toAttributeValue() }

/**
 * Converts this map into `Map<String, AttributeValue>`.
 *
 * ## Behavior and contract
 * - Returns a new map by converting keys with `toString()` and values with [toAttributeValue].
 *
 * ```kotlin
 * val attrMap = mapOf("id" to "u1", "age" to 30).toAttributeValueMap()
 * // attrMap["id"] == AttributeValue.S("u1")
 * // attrMap["age"] == AttributeValue.N("30")
 * ```
 */
inline fun <K: Any, V> Map<K, V>.toAttributeValueMap(): Map<String, AttributeValue> =
    this.entries.associate { it.key.toString() to it.value.toAttributeValue() }
