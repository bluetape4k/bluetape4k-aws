package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.DeleteBackupRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [DeleteBackupRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [backupArn] is blank.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = deleteBackupRequestOf("arn:aws:dynamodb:us-east-1:123456789012:table/users/backup/01234567890123-abc")
 * // req.backupArn == "arn:aws:dynamodb:us-east-1:123456789012:table/users/backup/01234567890123-abc"
 * ```
 *
 * @param backupArn ARN of the backup to delete. Blank values throw.
 */
inline fun deleteBackupRequestOf(
    backupArn: String,
    crossinline builder: DeleteBackupRequest.Builder.() -> Unit = {},
): DeleteBackupRequest {
    backupArn.requireNotBlank("backupArn")

    return DeleteBackupRequest {
        this.backupArn = backupArn

        builder()
    }
}
