package io.bluetape4k.aws.kms.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kms.model.EnableKeyRequest

/**
 * Creates an [EnableKeyRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [EnableKeyRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = enableKeyRequest {
 *     keyId("key-id")
 * }
 * // request.keyId() == "key-id"
 * ```
 */
inline fun enableKeyRequest(
    builder: EnableKeyRequest.Builder.() -> Unit,
): EnableKeyRequest =
    EnableKeyRequest.builder().apply(builder).build()

/**
 * Creates an [EnableKeyRequest] by specifying a key ID.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [keyId] is blank.
 * - When validation passes, sets the value on [EnableKeyRequest.Builder.keyId].
 *
 * ```kotlin
 * val request = enableKeyRequestOf("key-id")
 * // request.keyId() == "key-id"
 * ```
 */
fun enableKeyRequestOf(keyId: String): EnableKeyRequest {
    keyId.requireNotBlank("keyId")

    return enableKeyRequest {
        keyId(keyId)
    }
}
