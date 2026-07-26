package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest

/**
 * Builds a DynamoDB [GetItemRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Pass the partition key, plus sort key when needed, as `Map<String, AttributeValue>` through [key].
 * - Additional settings can be supplied through [builder].
 *
 * ```kotlin
 * val req = getItemRequestOf(key = mapOf("id" to AttributeValue.S("u1"))) {
 *     tableName = "users"
 * }
 * ```
 */
@JvmName("getItemRequestOfString")
inline fun getItemRequestOf(
    attributesToGet: List<String>? = null,
    consistentRead: Boolean? = null,
    expressionAttributeNames: Map<String, String>? = null,
    key: Map<String, AttributeValue>? = null,
    crossinline builder: GetItemRequest.Builder.() -> Unit = {},
): GetItemRequest {

    return GetItemRequest {
        this.attributesToGet = attributesToGet
        this.consistentRead = consistentRead
        this.expressionAttributeNames = expressionAttributeNames
        this.key = key

        builder()
    }
}

@JvmName("getItemRequestOfAny")
inline fun getItemRequestOf(
    attributesToGet: List<String>? = null,
    consistentRead: Boolean? = null,
    expressionAttributeNames: Map<String, String>? = null,
    key: Map<String, Any>? = null,
    crossinline builder: GetItemRequest.Builder.() -> Unit = {},
): GetItemRequest = getItemRequestOf(
    attributesToGet,
    consistentRead,
    expressionAttributeNames,
    key?.toAttributeValueMap(),
    builder
)
