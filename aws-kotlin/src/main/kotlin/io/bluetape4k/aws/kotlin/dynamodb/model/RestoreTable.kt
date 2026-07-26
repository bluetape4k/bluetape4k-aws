package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.RestoreTableFromBackupRequest
import aws.sdk.kotlin.services.dynamodb.model.RestoreTableToPointInTimeRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [RestoreTableFromBackupRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [backupArn] is blank.
 * - Throws `IllegalArgumentException` when [targetTableName] is blank.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = restoreTableFromBackupRequestOf(
 *     backupArn = "arn:aws:dynamodb:us-east-1:123456789012:table/users/backup/01234567890123-abc",
 *     targetTableName = "users-restored"
 * )
 * // req.targetTableName == "users-restored"
 * ```
 *
 * @param backupArn ARN of the source backup to restore from. Blank values throw.
 * @param targetTableName target table name to restore. Blank values throw.
 */
inline fun restoreTableFromBackupRequestOf(
    backupArn: String,
    targetTableName: String,
    crossinline builder: RestoreTableFromBackupRequest.Builder.() -> Unit = {},
): RestoreTableFromBackupRequest {
    backupArn.requireNotBlank("backupArn")
    targetTableName.requireNotBlank("targetTableName")

    return RestoreTableFromBackupRequest {
        this.backupArn = backupArn
        this.targetTableName = targetTableName

        builder()
    }
}

/**
 * Builds a DynamoDB [RestoreTableToPointInTimeRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [targetTableName] is blank.
 * - Specify either [sourceTableArn] or [sourceTableName] to set the restore source.
 * - When [useLatestRestorableTime] is true, restores to the latest restorable point in time.
 *
 * ```kotlin
 * val req = restoreTableToPointInTimeRequestOf(
 *     sourceTableName = "users",
 *     targetTableName = "users-restored",
 *     useLatestRestorableTime = true
 * ) {}
 * // req.targetTableName == "users-restored"
 * // req.useLatestRestorableTime == true
 * ```
 *
 * @param sourceTableArn ARN of the source table to restore from.
 * @param sourceTableName source table name to restore from.
 * @param targetTableName target table name to restore. Blank values throw.
 * @param useLatestRestorableTime whether to use the latest restorable point in time.
 */
inline fun restoreTableToPointInTimeRequestOf(
    sourceTableArn: String? = null,
    sourceTableName: String? = null,
    targetTableName: String? = null,
    useLatestRestorableTime: Boolean? = null,
    crossinline builder: RestoreTableToPointInTimeRequest.Builder.() -> Unit,
): RestoreTableToPointInTimeRequest {

    targetTableName.requireNotBlank("targetTableName")

    return RestoreTableToPointInTimeRequest {
        this.sourceTableArn = sourceTableArn
        this.sourceTableName = sourceTableName
        this.targetTableName = targetTableName
        this.useLatestRestorableTime = useLatestRestorableTime

        builder()
    }
}
