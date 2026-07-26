package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AutoScalingSettingsUpdate
import aws.sdk.kotlin.services.dynamodb.model.GlobalTableGlobalSecondaryIndexSettingsUpdate
import aws.sdk.kotlin.services.dynamodb.model.ReplicaUpdate
import aws.sdk.kotlin.services.dynamodb.model.UpdateGlobalTableRequest
import aws.sdk.kotlin.services.dynamodb.model.UpdateGlobalTableSettingsRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [UpdateGlobalTableRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [globalTableName] is blank.
 * - Creates the request without replica updates when [replicaUpdates] is null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = updateGlobalTableRequestOf(
 *     globalTableName = "global-users",
 *     replicaUpdates = listOf(replicaUpdateOf(create = CreateReplicaAction { regionName = "us-west-2" }))
 * )
 * // req.globalTableName == "global-users"
 * // req.replicaUpdates?.size == 1
 * ```
 *
 * @param globalTableName global table name to update. Blank values throw.
 * @param replicaUpdates replica add/delete update list.
 */
inline fun updateGlobalTableRequestOf(
    globalTableName: String,
    replicaUpdates: List<ReplicaUpdate>?,
    crossinline builder: UpdateGlobalTableRequest.Builder.() -> Unit = {},
): UpdateGlobalTableRequest {
    globalTableName.requireNotBlank("globalTableName")

    return UpdateGlobalTableRequest {
        this.globalTableName = globalTableName
        this.replicaUpdates = replicaUpdates

        builder()
    }
}

/**
 * Builds a DynamoDB [UpdateGlobalTableSettingsRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [globalTableName] is blank.
 * - Null setting values are omitted from the request.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = updateGlobalTableSettingsRequestOf(
 *     globalTableName = "global-users",
 *     autoScalingSettingsUpdate = AutoScalingSettingsUpdate { minimumUnits = 5L }
 * )
 * // req.globalTableName == "global-users"
 * ```
 *
 * @param globalTableName global table name whose settings will be updated. Blank values throw.
 * @param autoScalingSettingsUpdate write-capacity auto scaling settings.
 * @param gsIndexSettingsUpdates global secondary index settings update list.
 */
inline fun updateGlobalTableSettingsRequestOf(
    globalTableName: String,
    autoScalingSettingsUpdate: AutoScalingSettingsUpdate? = null,
    gsIndexSettingsUpdates: List<GlobalTableGlobalSecondaryIndexSettingsUpdate>? = null,
    crossinline builder: UpdateGlobalTableSettingsRequest.Builder.() -> Unit = {},
): UpdateGlobalTableSettingsRequest {
    globalTableName.requireNotBlank("globalTableName")

    return UpdateGlobalTableSettingsRequest {
        this.globalTableName = globalTableName
        this.globalTableProvisionedWriteCapacityAutoScalingSettingsUpdate = autoScalingSettingsUpdate
        this.globalTableGlobalSecondaryIndexSettingsUpdate = gsIndexSettingsUpdates

        builder()
    }
}
