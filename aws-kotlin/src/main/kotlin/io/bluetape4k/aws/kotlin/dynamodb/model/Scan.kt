package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [ScanRequest] with a DSL block. [AttributeValue] start-key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - When [exclusiveStartKey] is set, pagination starts after that key.
 * - Additional fields such as filter expressions and segments can be set through [builder].
 *
 * ```kotlin
 * val req = scanRequestOf(
 *     tableName = "users",
 *     attributesToGet = listOf("id", "name"),
 *     indexName = "status-index"
 * )
 * // req.tableName == "users"
 * // req.indexName == "status-index"
 * ```
 *
 * @param tableName DynamoDB table name to scan. Blank values throw.
 * @param attributesToGet attribute names to return. This is legacy; prefer projection expressions.
 * @param exclusiveStartKey pagination start key.
 * @param indexName secondary index name to scan.
 */
@JvmName("scanRequestOfAttributeValue")
inline fun scanRequestOf(
    tableName: String,
    attributesToGet: List<String>? = null,
    exclusiveStartKey: Map<String, AttributeValue>? = null,
    indexName: String? = null,
    crossinline builder: ScanRequest.Builder.() -> Unit = {},
): ScanRequest {
    tableName.requireNotBlank("tableName")

    return ScanRequest {
        this.tableName = tableName
        this.attributesToGet = attributesToGet
        this.exclusiveStartKey = exclusiveStartKey
        this.indexName = indexName

        builder()
    }
}

/**
 * Builds a DynamoDB [ScanRequest] with a DSL block. Any? start-key overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Each value in [exclusiveStartKey] is converted into [AttributeValue] through [toAttributeValueMap].
 * - Additional fields can be set through [builder].
 *
 * ```kotlin
 * val req = scanRequestOf(
 *     tableName = "users",
 *     exclusiveStartKey = mapOf("id" to "u100")
 * )
 * // req.exclusiveStartKey?.get("id") == AttributeValue.S("u100")
 * ```
 *
 * @param tableName DynamoDB table name to scan. Blank values throw.
 * @param attributesToGet attribute names to return.
 * @param exclusiveStartKey pagination start key. Converted to [AttributeValue] automatically.
 * @param indexName secondary index name to scan.
 */
@JvmName("scanRequestOfAny")
inline fun scanRequestOf(
    tableName: String,
    attributesToGet: List<String>? = null,
    exclusiveStartKey: Map<String, Any?>? = null,
    indexName: String? = null,
    crossinline builder: ScanRequest.Builder.() -> Unit = {},
): ScanRequest {
    tableName.requireNotBlank("tableName")

    return scanRequestOf(
        tableName,
        attributesToGet,
        exclusiveStartKey?.toAttributeValueMap(),
        indexName,
        builder
    )
}
