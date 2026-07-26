package io.bluetape4k.aws.dynamodb.model

import io.bluetape4k.aws.core.toSdkBytes
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val key = Key {
 *  partitionValue("Hello, World!")
 *  sortValue(42)
 * }
 *
 * check(key.partitionKeyValue().s() == "Hello, World!")
 * ```
 * @param builder Parameter.
 * @return Return value.
 */
inline fun key(builder: Key.Builder.() -> Unit): Key {
    return Key.builder().apply(builder).build()
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val key = keyOf(AttributeValue.fromS("pk#1"), AttributeValue.fromN("42"))
 * check(key.sortKeyValue().n() == "42")
 * ```
 *
 * @param partitionKey Parameter.
 * @param sortValue Parameter.
 *
 * @return Return value.
 */
fun keyOf(partitionKey: AttributeValue, sortValue: AttributeValue? = null): Key =
    key {
        partitionValue(partitionKey)
        sortValue(sortValue)
    }

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val key = keyOf("pk#1", 42)
 * check(key.partitionKeyValue().s() == "pk#1")
 * ```
 *
 * @param partitionValue Parameter.
 * @param sortValue Parameter.
 *
 * @return Return value.
 */
fun keyOf(partitionValue: Any, sortValue: Any? = null): Key =
    key {
        when (partitionValue) {
            is Number    -> partitionValue(partitionValue)
            is ByteArray -> partitionValue(partitionValue.toSdkBytes())
            else         -> partitionValue(partitionValue.toString())
        }
        sortValue?.let {
            when (sortValue) {
                is Number    -> sortValue(sortValue)
                is ByteArray -> sortValue(sortValue.toSdkBytes())
                else         -> sortValue(sortValue.toString())
            }
        }
    }
