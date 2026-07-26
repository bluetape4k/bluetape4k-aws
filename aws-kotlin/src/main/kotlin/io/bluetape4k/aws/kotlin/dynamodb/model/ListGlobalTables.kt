package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.ListGlobalTablesRequest

/**
 * Builds a DynamoDB [ListGlobalTablesRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - When every parameter is null, requests the full global table list from the first page.
 * - When [exclusiveStartGlobalTableName] is set, pagination starts after that table name.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = listGlobalTableRequestOf(limit = 20, regionName = "ap-northeast-2")
 * // req.limit == 20
 * // req.regionName == "ap-northeast-2"
 * ```
 *
 * @param exclusiveStartGlobalTableName global table name to start pagination after.
 * @param regionName AWS region name to query.
 * @param limit maximum number of global tables to return.
 */
inline fun listGlobalTableRequestOf(
    exclusiveStartGlobalTableName: String? = null,
    regionName: String? = null,
    limit: Int? = null,
    crossinline builder: ListGlobalTablesRequest.Builder.() -> Unit = {},
): ListGlobalTablesRequest = ListGlobalTablesRequest {
    this.exclusiveStartGlobalTableName = exclusiveStartGlobalTableName
    this.regionName = regionName
    this.limit = limit

    builder()
}
