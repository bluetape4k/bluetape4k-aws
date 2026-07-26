package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.CreateBackupRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [CreateBackupRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Throws `IllegalArgumentException` when [backupName] is blank.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = createBackupRequestOf("users", "users-backup-2024")
 * // req.tableName == "users"
 * // req.backupName == "users-backup-2024"
 * ```
 *
 * @param tableName DynamoDB table name to back up. Blank values throw.
 * @param backupName backup name to create. Blank values throw.
 */
inline fun createBackupRequestOf(
    tableName: String,
    backupName: String,
    crossinline builder: CreateBackupRequest.Builder.() -> Unit = {},
): CreateBackupRequest {
    tableName.requireNotBlank("tableName")
    backupName.requireNotBlank("backupName")

    return CreateBackupRequest {
        this.tableName = tableName
        this.backupName = backupName

        builder()
    }
}
