package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.TableClass
import aws.sdk.kotlin.services.dynamodb.model.UpdateTableRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [UpdateTableRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Creates the request without a table-class change when [tableClass] is null.
 * - Additional fields such as provisioned throughput and GSI updates can be set through [builder].
 *
 * ```kotlin
 * val req = updateTableRequestOf("users", TableClass.Standard) {
 *     provisionedThroughput = provisionedThroughputOf(readCapacityUnits = 10L, writeCapacityUnits = 5L)
 * }
 * // req.tableName == "users"
 * // req.tableClass == TableClass.Standard
 * ```
 *
 * @param tableName DynamoDB table name to update. Blank values throw.
 * @param tableClass table class to change to.
 */
inline fun updateTableRequestOf(
    tableName: String,
    tableClass: TableClass? = null,
    crossinline builder: UpdateTableRequest.Builder.() -> Unit = {},
): UpdateTableRequest {
    tableName.requireNotBlank("tableName")

    return UpdateTableRequest {
        this.tableName = tableName
        this.tableClass = tableClass

        builder()
    }
}
