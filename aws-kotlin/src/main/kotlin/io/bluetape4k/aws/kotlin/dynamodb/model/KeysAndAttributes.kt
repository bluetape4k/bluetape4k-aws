package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.KeysAndAttributes
import io.bluetape4k.support.requireNotEmpty

/**
 * Builds DynamoDB [KeysAndAttributes] with a DSL block. [AttributeValue] key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [keys] is empty.
 * - Additional fields such as projection expressions can be overridden through [builder].
 *
 * ```kotlin
 * val kaa = keysAndAttributesOf(
 *     listOf(mapOf("id" to AttributeValue.S("u1")), mapOf("id" to AttributeValue.S("u2")))
 * )
 * // kaa.keys?.size == 2
 * ```
 *
 * @param keys primary key map list for items to read. Empty values throw.
 */
@JvmName("keysAndAttributesOfAttributeValue")
inline fun keysAndAttributesOf(
    keys: List<Map<String, AttributeValue>>,
    crossinline builder: KeysAndAttributes.Builder.() -> Unit = {},
): KeysAndAttributes {
    keys.requireNotEmpty("keys")

    return KeysAndAttributes {
        this.keys = keys

        builder()
    }
}

/**
 * Builds DynamoDB [KeysAndAttributes] with a DSL block. Any? key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [keys] is empty.
 * - Each value in each key map is converted into [AttributeValue] through [toAttributeValue].
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val kaa = keysAndAttributesOf(listOf(mapOf("id" to "u1"), mapOf("id" to "u2")))
 * // kaa.keys?.first()?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param keys primary key map list for items to read. Empty values throw and values convert to [AttributeValue].
 */
@JvmName("keysAndAttributesOfAny")
inline fun keysAndAttributesOf(
    keys: List<Map<String, Any?>>,
    crossinline builder: KeysAndAttributes.Builder.() -> Unit = {},
): KeysAndAttributes {
    keys.requireNotEmpty("keys")

    return keysAndAttributesOf(
        keys.map { it.mapValues { it.value.toAttributeValue() } },
        builder
    )
}
