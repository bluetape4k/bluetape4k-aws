package io.bluetape4k.aws.dynamodb.model

import io.bluetape4k.aws.core.toSdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val strValue = attributeValue { s("Hello, World!") }
 * val intValue = attributeValue { n("123") }
 * val boolValue = attributeValue { bool(true) }
 * ```
 *
 * @param builder Parameter.
 */
inline fun attributeValue(
    builder: AttributeValue.Builder.() -> Unit,
): AttributeValue {
    return AttributeValue.builder().apply(builder).build()
}

/**
 * See the API documentation for details.
 */
fun ByteArray.toAttributeValue(): AttributeValue = AttributeValue.builder()
    .b(this.toSdkBytes())
    .build()

/**
 * See the API documentation for details.
 */
fun ByteBuffer.toAttributeValue(): AttributeValue = AttributeValue.builder()
    .b(this.toSdkBytes())
    .build()

/**
 * See the API documentation for details.
 */
fun String.toAttributeValue(): AttributeValue = AttributeValue.builder()
    .s(this)
    .build()

/**
 * See the API documentation for details.
 */
fun Number.toAttributeValue(): AttributeValue = AttributeValue.builder()
    .n(this.toString())
    .build()

/**
 * See the API documentation for details.
 */
fun Boolean.toAttributeValue(): AttributeValue = AttributeValue.builder()
    .bool(this)
    .build()

/**
 * See the API documentation for details.
 */
fun Boolean.toNullAttributeValue(): AttributeValue = AttributeValue.builder()
    .nul(this)
    .build()

/**
 * See the API documentation for details.
 */
fun Iterable<*>.toAttributeValue(): AttributeValue = AttributeValue.builder()
    .l(this.map { it.toAttributeValue() })
    .build()

/**
 * See the API documentation for details.
 */
fun Map<*, *>.toAttributeValue(): AttributeValue {
    val mapped = this.entries.associate { entry ->
        val key = entry.key ?: throw IllegalArgumentException("Map key must be non-null String")
        require(key is String) { "Map key must be String but was ${key::class.java.name}" }
        key to entry.value.toAttributeValue()
    }
    return AttributeValue.builder()
        .m(mapped)
        .build()
}

/**
 * See the API documentation for details.
 */
fun InputStream.toAttributeValue(): AttributeValue = AttributeValue.builder()
    .b(toSdkBytes())
    .build()

/**
 * See the API documentation for details.
 * See the API documentation for details.
 *
 * See the API documentation for details.
 * See the API documentation for details.
 */
fun <T> T.toAttributeValue(): AttributeValue = when (this) {
    null          -> AttributeValue.builder().nul(true).build()
    is ByteArray  -> toAttributeValue()
    is ByteBuffer -> toAttributeValue()
    is String     -> toAttributeValue()
    is Number     -> toAttributeValue()
    is Boolean    -> toAttributeValue()
    is Iterable<*> -> toAttributeValue()
    is Map<*, *>  -> toAttributeValue()
    is InputStream -> toAttributeValue()
    else          -> attributeValue { s(this@toAttributeValue.toString()) }
}
