package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.CreateGlobalTableRequest
import aws.sdk.kotlin.services.dynamodb.model.Replica
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [CreateGlobalTableRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [globalTableName] is blank.
 * - Creates the request without a replication group when [replicationGroup] is null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = createGlobalTableRequestOf(
 *     globalTableName = "global-users",
 *     replicationGroup = listOf(replicaOf("us-east-1"), replicaOf("ap-northeast-2"))
 * )
 * // req.globalTableName == "global-users"
 * // req.replicationGroup?.size == 2
 * ```
 *
 * @param globalTableName global table name to create. Blank values throw.
 * @param replicationGroup [Replica] list for regions to replicate.
 */
inline fun createGlobalTableRequestOf(
    globalTableName: String,
    replicationGroup: List<Replica>? = null,
    crossinline builder: CreateGlobalTableRequest.Builder.() -> Unit = {},
): CreateGlobalTableRequest {
    globalTableName.requireNotBlank("globalTableName")

    return CreateGlobalTableRequest {
        this.globalTableName = globalTableName
        this.replicationGroup = replicationGroup

        builder()
    }
}
