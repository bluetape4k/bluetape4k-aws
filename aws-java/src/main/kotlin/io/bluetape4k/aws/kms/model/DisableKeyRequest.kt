package io.bluetape4k.aws.kms.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kms.model.DisableKeyRequest

/**
 * Creates a [DisableKeyRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [DisableKeyRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = disableKeyRequest {
 *     keyId("arn:aws:kms:ap-northeast-2:111122223333:key/abcd")
 * }
 * // request.keyId().contains("key/") == true
 * ```
 */
inline fun disableKeyRequest(
    builder: DisableKeyRequest.Builder.() -> Unit,
): DisableKeyRequest =
    DisableKeyRequest.builder().apply(builder).build()

/**
 * Creates a [DisableKeyRequest] by specifying a key ID.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [keyId] is blank.
 * - When validation passes, sets the value on [DisableKeyRequest.Builder.keyId].
 *
 * ```kotlin
 * val request = disableKeyRequestOf("key-id")
 * // request.keyId() == "key-id"
 * ```
 *
 */
fun disableKeyRequestOf(keyId: String): DisableKeyRequest {
    keyId.requireNotBlank("keyId")

    return disableKeyRequest {
        keyId(keyId)
    }
}
