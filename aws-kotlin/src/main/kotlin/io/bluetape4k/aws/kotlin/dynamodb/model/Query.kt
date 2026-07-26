package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [QueryRequest] with a DSL block. [AttributeValue] start-key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - When [exclusiveStartKey] is set, pagination starts after that key.
 * - Additional fields such as key condition expressions and filter expressions can be set through [builder].
 *
 * ```kotlin
 * val req = queryRequestOf(
 *     tableName = "users",
 *     attributesToGet = listOf("id", "name")
 * ) {
 *     keyConditionExpression = "id = :id"
 *     expressionAttributeValues = mapOf(":id" to AttributeValue.S("u1"))
 * }
 * // req.tableName == "users"
 * ```
 *
 * @param tableName DynamoDB table name to query. Blank values throw.
 * @param attributesToGet attribute names to return. This is legacy; prefer projection expressions.
 * @param exclusiveStartKey pagination start key.
 */
@JvmName("queryRequestOfAttributeValue")
inline fun queryRequestOf(
    tableName: String,
    attributesToGet: List<String>? = null,
    exclusiveStartKey: Map<String, AttributeValue>? = null,
    crossinline builder: QueryRequest.Builder.() -> Unit = {},
): QueryRequest {
    tableName.requireNotBlank("tableName")

    return QueryRequest {
        this.tableName = tableName
        this.attributesToGet = attributesToGet
        this.exclusiveStartKey = exclusiveStartKey

        builder()
    }
}

/**
 * Builds a DynamoDB [QueryRequest] with a DSL block. Any? start-key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Each value in [exclusiveStartKey] is converted into [AttributeValue] through [toAttributeValueMap].
 * - Additional fields can be set through [builder].
 *
 * ```kotlin
 * val req = queryRequestOf(
 *     tableName = "users",
 *     exclusiveStartKey = mapOf("id" to "u1")
 * )
 * // req.exclusiveStartKey?.get("id") == AttributeValue.S("u1")
 * ```
 *
 * @param tableName DynamoDB table name to query. Blank values throw.
 * @param attributesToGet attribute names to return.
 * @param exclusiveStartKey pagination start key. Converted to [AttributeValue] automatically.
 */
@JvmName("queryRequestOfAny")
inline fun queryRequestOf(
    tableName: String,
    attributesToGet: List<String>? = null,
    exclusiveStartKey: Map<String, Any?>? = null,
    crossinline builder: QueryRequest.Builder.() -> Unit = {},
): QueryRequest =
    queryRequestOf(
        tableName,
        attributesToGet,
        exclusiveStartKey?.toAttributeValueMap(),
        builder,
    )
