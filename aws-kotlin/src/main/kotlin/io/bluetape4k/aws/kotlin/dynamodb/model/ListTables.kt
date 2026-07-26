package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.ListTablesRequest

/**
 * Builds a DynamoDB [ListTablesRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - When every parameter is null, requests the full table list from the first page.
 * - When [exclusiveStartTableName] is set, pagination starts after that table name.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = listTablesRequestOf(limit = 50)
 * // req.limit == 50
 * // req.exclusiveStartTableName == null
 * ```
 *
 * @param exclusiveStartTableName table name to start pagination after.
 * @param limit maximum number of tables to return.
 */
inline fun listTablesRequestOf(
    exclusiveStartTableName: String? = null,
    limit: Int? = null,
    crossinline builder: ListTablesRequest.Builder.() -> Unit = {},
): ListTablesRequest = ListTablesRequest {
    this.exclusiveStartTableName = exclusiveStartTableName
    this.limit = limit

    builder()
}
