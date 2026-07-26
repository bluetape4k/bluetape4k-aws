package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.DeleteTableRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [DeleteTableRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = deleteTableRequestOf("users")
 * // req.tableName == "users"
 * ```
 *
 * @param tableName DynamoDB table name to delete. Blank values throw.
 */
inline fun deleteTableRequestOf(
    tableName: String,
    crossinline builder: DeleteTableRequest.Builder.() -> Unit = {},
): DeleteTableRequest {
    tableName.requireNotBlank("tableName")

    return DeleteTableRequest {
        this.tableName = tableName

        builder()
    }
}
