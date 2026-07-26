package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.DeleteRequest
import aws.sdk.kotlin.services.dynamodb.model.PutRequest
import aws.sdk.kotlin.services.dynamodb.model.WriteRequest

/**
 * Builds a DynamoDB [WriteRequest] from a [PutRequest].
 *
 * ## Behavior and contract
 * - Sets [putRequest] on the `putRequest` field of [WriteRequest].
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = writeRequestOf(putRequestOf(mapOf("id" to AttributeValue.S("u1"))))
 * // req.putRequest?.item?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param putRequest [PutRequest] object that defines the put request.
 */
@JvmName("writeRequestOfPut")
inline fun writeRequestOf(
    putRequest: PutRequest,
    crossinline builder: WriteRequest.Builder.() -> Unit = {},
): WriteRequest = WriteRequest {
    this.putRequest = putRequest
    builder()
}

/**
 * Builds a DynamoDB [WriteRequest] from a [DeleteRequest].
 *
 * ## Behavior and contract
 * - Sets [deleteRequest] on the `deleteRequest` field of [WriteRequest].
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = writeRequestOf(deleteRequestOf(mapOf("id" to AttributeValue.S("u1"))))
 * // req.deleteRequest?.key?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param deleteRequest [DeleteRequest] object that defines the delete request.
 */
@JvmName("writeRequestOfDelete")
inline fun writeRequestOf(
    deleteRequest: DeleteRequest,
    crossinline builder: WriteRequest.Builder.() -> Unit = {},
): WriteRequest = WriteRequest {
    this.deleteRequest = deleteRequest
    builder()
}

/**
 * Creates a Put-type DynamoDB [WriteRequest] from an Any? attribute map.
 *
 * ## Behavior and contract
 * - Each value in [item] is converted into [AttributeValue] through [toAttributeValueMap].
 *
 * ```kotlin
 * val req = writePutRequestOf(mapOf("id" to "u1"))
 * // req.putRequest?.item?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param item attribute map for the item to store. Converted to [AttributeValue] automatically.
 */
@JvmName("putRequestOfMap")
fun writePutRequestOf(item: Map<String, Any?>): WriteRequest =
    writeRequestOf(putRequestOf(item))

/**
 * Creates a Put-type DynamoDB [WriteRequest] from an [AttributeValue] attribute map.
 *
 * ## Behavior and contract
 * - [item] is set directly as the [PutRequest] item without conversion.
 *
 * ```kotlin
 * val req = writePutRequestOf(mapOf("id" to AttributeValue.S("u1")))
 * // req.putRequest?.item?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param item [AttributeValue] attribute map for the item to store.
 */
@JvmName("putRequestOfAttributeValue")
fun writePutRequestOf(item: Map<String, AttributeValue>): WriteRequest =
    writeRequestOf(putRequestOf(item))

/**
 * Creates a Delete-type DynamoDB [WriteRequest] from an Any? key map.
 *
 * ## Behavior and contract
 * - Each value in [key] is converted into [AttributeValue] through [toAttributeValueMap].
 *
 * ```kotlin
 * val req = writeDeleteRequestOf(mapOf("id" to "u1"))
 * // req.deleteRequest?.key?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param key primary key map for the item to delete. Converted to [AttributeValue] automatically.
 */
@JvmName("deleteRequestOfMap")
fun writeDeleteRequestOf(key: Map<String, Any?>): WriteRequest =
    writeRequestOf(deleteRequest = deleteRequestOf(key))

/**
 * Creates a Delete-type DynamoDB [WriteRequest] from an [AttributeValue] key map.
 *
 * ## Behavior and contract
 * - [key] is set directly as the [DeleteRequest] key without conversion.
 *
 * ```kotlin
 * val req = writeDeleteRequestOf(mapOf("id" to AttributeValue.S("u1")))
 * // req.deleteRequest?.key?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param key [AttributeValue] primary key map for the item to delete.
 */
@JvmName("deleteRequestOfAttributeValue")
fun writeDeleteRequestOf(key: Map<String, AttributeValue>): WriteRequest =
    writeRequestOf(deleteRequest = deleteRequestOf(key))
