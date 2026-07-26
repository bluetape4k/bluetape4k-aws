package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.PointInTimeRecoverySpecification
import aws.sdk.kotlin.services.dynamodb.model.UpdateContinuousBackupsRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [UpdateContinuousBackupsRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Creates the request without PITR settings when [pointInTimeRecoverySpecification] is null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = updateContinuousBackupsRequestOf(
 *     tableName = "users",
 *     pointInTimeRecoverySpecification = PointInTimeRecoverySpecification { pointInTimeRecoveryEnabled = true }
 * )
 * // req.tableName == "users"
 * // req.pointInTimeRecoverySpecification?.pointInTimeRecoveryEnabled == true
 * ```
 *
 * @param tableName DynamoDB table name whose PITR settings will be updated. Blank values throw.
 * @param pointInTimeRecoverySpecification point-in-time recovery (PITR) enablement settings.
 */
inline fun updateContinuousBackupsRequestOf(
    tableName: String,
    pointInTimeRecoverySpecification: PointInTimeRecoverySpecification? = null,
    crossinline builder: UpdateContinuousBackupsRequest.Builder.() -> Unit = {},
): UpdateContinuousBackupsRequest {
    tableName.requireNotBlank("tableName")

    return UpdateContinuousBackupsRequest {
        this.tableName = tableName
        this.pointInTimeRecoverySpecification = pointInTimeRecoverySpecification

        builder()
    }
}
