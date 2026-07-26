package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.TimeToLiveSpecification
import aws.sdk.kotlin.services.dynamodb.model.UpdateTimeToLiveRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [UpdateTimeToLiveRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Creates the request without TTL settings when [timeToLiveSpecification] is null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = updateTimeToLiveRequestOf(
 *     tableName = "users",
 *     timeToLiveSpecification = TimeToLiveSpecification {
 *         enabled = true
 *         attributeName = "expiresAt"
 *     }
 * )
 * // req.tableName == "users"
 * // req.timeToLiveSpecification?.enabled == true
 * ```
 *
 * @param tableName DynamoDB table name whose TTL settings will be updated. Blank values throw.
 * @param timeToLiveSpecification TTL enablement and attribute-name settings.
 */
inline fun updateTimeToLiveRequestOf(
    tableName: String,
    timeToLiveSpecification: TimeToLiveSpecification? = null,
    crossinline builder: UpdateTimeToLiveRequest.Builder.() -> Unit = {},
): UpdateTimeToLiveRequest {
    tableName.requireNotBlank("tableName")

    return UpdateTimeToLiveRequest {
        this.tableName = tableName
        this.timeToLiveSpecification = timeToLiveSpecification

        builder()
    }
}
