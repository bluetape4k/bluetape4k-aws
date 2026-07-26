package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.Delete
import aws.sdk.kotlin.services.dynamodb.model.DeleteRequest
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

/**
 * Builds a DynamoDB [Delete] with a DSL block. [AttributeValue] key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - [key] is the primary key map for the item to delete and is omitted when null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val del = deleteOf("users", mapOf("id" to AttributeValue.S("u1")))
 * // del.tableName == "users"
 * // del.key?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param tableName target DynamoDB table name for deletion. Blank values throw.
 * @param key primary key map for the item to delete.
 */
@JvmName("deleteOfAttributeValue")
inline fun deleteOf(
    tableName: String,
    key: Map<String, AttributeValue>? = null,
    crossinline builder: Delete.Builder.() -> Unit = {},
): Delete {
    tableName.requireNotBlank("tableName")

    return Delete {
        this.tableName = tableName
        this.key = key

        builder()
    }
}

/**
 * Builds a DynamoDB [Delete] with a DSL block. Any? key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Each value in [key] is converted into [AttributeValue] through [toAttributeValueMap].
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val del = deleteOf("users", mapOf("id" to "u1"))
 * // del.key?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param tableName target DynamoDB table name for deletion. Blank values throw.
 * @param key primary key map for the item to delete. Converted to [AttributeValue] automatically.
 */
@JvmName("deleteOfAny")
inline fun deleteOf(
    tableName: String,
    key: Map<String, Any?>? = null,
    crossinline builder: Delete.Builder.() -> Unit = {},
): Delete {
    tableName.requireNotBlank("tableName")

    return Delete {
        this.tableName = tableName
        this.key = key?.toAttributeValueMap()

        builder()
    }
}

/**
 * Builds a DynamoDB [DeleteRequest] with a DSL block. [AttributeValue] key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [key] is empty.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = deleteRequestOf(mapOf("id" to AttributeValue.S("u1")))
 * // req.key?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param key primary key map for the item to delete. Empty values throw.
 */
@JvmName("deleteRequestOfAttributeValue")
inline fun deleteRequestOf(
    key: Map<String, AttributeValue>,
    crossinline builder: DeleteRequest.Builder.() -> Unit = {},
): DeleteRequest {
    key.requireNotEmpty("key")

    return DeleteRequest {
        this.key = key

        builder()
    }
}

/**
 * Builds a DynamoDB [DeleteRequest] with a DSL block. Any? key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [key] is empty.
 * - Each value in [key] is converted into [AttributeValue] through [toAttributeValueMap].
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = deleteRequestOf(mapOf("id" to "u1"))
 * // req.key?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param key primary key map for the item to delete. Empty values throw and values convert to [AttributeValue].
 */
@JvmName("deleteRequestOfAny")
inline fun deleteRequestOf(
    key: Map<String, Any?>,
    crossinline builder: DeleteRequest.Builder.() -> Unit = {},
): DeleteRequest {
    key.requireNotEmpty("key")

    return DeleteRequest {
        this.key = key.toAttributeValueMap()

        builder()
    }
}
