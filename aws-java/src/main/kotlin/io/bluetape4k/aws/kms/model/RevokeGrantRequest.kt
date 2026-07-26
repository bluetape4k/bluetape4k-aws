package io.bluetape4k.aws.kms.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kms.model.RevokeGrantRequest

/**
 * Creates a [RevokeGrantRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [RevokeGrantRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = revokeGrantRequest {
 *     keyId("key-id")
 *     grantId("grant-id")
 * }
 * // request.grantId() == "grant-id"
 * ```
 */
inline fun revokeGrantRequest(
    builder: RevokeGrantRequest.Builder.() -> Unit,
): RevokeGrantRequest =
    RevokeGrantRequest.builder().apply(builder).build()

/**
 * Creates a [RevokeGrantRequest] by specifying a key ID and grant ID.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [keyId] or [grantId] is blank.
 * - When validation passes, sets both fields and then runs [builder].
 *
 * ```kotlin
 * val request = revokeGrantRequestOf(
 *     keyId = "key-id",
 *     grantId = "grant-id"
 * )
 * // request.keyId() == "key-id"
 * ```
 */
fun revokeGrantRequestOf(
    keyId: String,
    grantId: String,
    builder: RevokeGrantRequest.Builder.() -> Unit = {},
): RevokeGrantRequest {
    keyId.requireNotBlank("keyId")
    grantId.requireNotBlank("grantId")

    return revokeGrantRequest {
        keyId(keyId)
        grantId(grantId)

        builder()
    }
}
