package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AutoScalingSettingsUpdate
import aws.sdk.kotlin.services.dynamodb.model.CreateReplicaAction
import aws.sdk.kotlin.services.dynamodb.model.DeleteReplicaAction
import aws.sdk.kotlin.services.dynamodb.model.Replica
import aws.sdk.kotlin.services.dynamodb.model.ReplicaGlobalSecondaryIndexSettingsUpdate
import aws.sdk.kotlin.services.dynamodb.model.ReplicaSettingsUpdate
import aws.sdk.kotlin.services.dynamodb.model.ReplicaUpdate
import aws.sdk.kotlin.services.dynamodb.model.TableClass
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [Replica] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [regionName] is blank.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val replica = replicaOf("ap-northeast-2")
 * // replica.regionName == "ap-northeast-2"
 * ```
 *
 * @param regionName AWS region name to replicate. Blank values throw.
 */
inline fun replicaOf(
    regionName: String,
    crossinline builder: Replica.Builder.() -> Unit = {},
): Replica {
    regionName.requireNotBlank("regionName")

    return Replica {
        this.regionName = regionName

        builder()
    }
}

/**
 * Builds a DynamoDB [ReplicaUpdate] with a DSL block.
 *
 * ## Behavior and contract
 * - Specify either [create] or [delete] to configure a replica add or delete operation.
 * - Creates an empty [ReplicaUpdate] when both values are null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val update = replicaUpdateOf(create = CreateReplicaAction { regionName = "us-west-2" })
 * // update.create?.regionName == "us-west-2"
 * ```
 *
 * @param create replica region settings to add.
 * @param delete replica region settings to delete.
 */
inline fun replicaUpdateOf(
    create: CreateReplicaAction? = null,
    delete: DeleteReplicaAction? = null,
    crossinline builder: ReplicaUpdate.Builder.() -> Unit = {},
): ReplicaUpdate = ReplicaUpdate {
    this.create = create
    this.delete = delete

    builder()
}

/**
 * Builds a DynamoDB [ReplicaSettingsUpdate] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [regionName] is blank.
 * - Null setting values are omitted from the request.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val settings = replicaSettingsUpdateOf(
 *     regionName = "ap-northeast-2",
 *     replicaProvisionedReadCapacityUnits = 10L
 * )
 * // settings.regionName == "ap-northeast-2"
 * // settings.replicaProvisionedReadCapacityUnits == 10L
 * ```
 *
 * @param regionName AWS region name of the replica whose settings will be updated. Blank values throw.
 * @param replicaGlobalSecondaryIndexSettingsUpdate GSI settings update list.
 * @param replicaProvisionedReadCapacityAutoScalingSettingsUpdate read-capacity auto scaling settings.
 * @param replicaProvisionedReadCapacityUnits provisioned read capacity units.
 * @param replicaTableClass replica table class.
 */
inline fun replicaSettingsUpdateOf(
    regionName: String,
    replicaGlobalSecondaryIndexSettingsUpdate: List<ReplicaGlobalSecondaryIndexSettingsUpdate>? = null,
    replicaProvisionedReadCapacityAutoScalingSettingsUpdate: AutoScalingSettingsUpdate? = null,
    replicaProvisionedReadCapacityUnits: Long? = null,
    replicaTableClass: TableClass? = null,
    crossinline builder: ReplicaSettingsUpdate.Builder.() -> Unit = {},
): ReplicaSettingsUpdate {
    regionName.requireNotBlank("regionName")

    return ReplicaSettingsUpdate.invoke {
        this.regionName = regionName
        this.replicaGlobalSecondaryIndexSettingsUpdate = replicaGlobalSecondaryIndexSettingsUpdate
        this.replicaProvisionedReadCapacityAutoScalingSettingsUpdate =
            replicaProvisionedReadCapacityAutoScalingSettingsUpdate
        this.replicaProvisionedReadCapacityUnits = replicaProvisionedReadCapacityUnits
        this.replicaTableClass = replicaTableClass

        builder()
    }
}
