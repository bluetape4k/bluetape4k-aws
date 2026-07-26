package io.bluetape4k.aws.kms.model

import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kms.model.DecryptRequest
import software.amazon.awssdk.services.kms.model.DryRunModifierType
import software.amazon.awssdk.services.kms.model.RecipientInfo

/**
 * Creates a [DecryptRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [DecryptRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = decryptRequest {
 *     keyId("key-id")
 * }
 * // request.keyId() == "key-id"
 * ```
 */
inline fun decryptRequest(
    builder: DecryptRequest.Builder.() -> Unit,
): DecryptRequest =
    DecryptRequest.builder().apply(builder).build()

/**
 * Creates a [DecryptRequest] by specifying primary parameters directly.
 *
 * ## Behavior/Contract
 * - Applies each argument to the same-named builder method only when it is non-null.
 * - Runs [builder] last.
 *
 * ```kotlin
 * val request = decryptRequestOf(
 *     keyId = "key-id",
 *     ciphertextBlob = SdkBytes.fromUtf8String("cipher-text")
 * )
 * // request.keyId() == "key-id"
 * ```
 */
inline fun decryptRequestOf(
    ciphertextBlob: SdkBytes? = null,
    encryptionContext: Map<String, String>? = null,
    grantTokens: Collection<String>? = null,
    keyId: String? = null,
    encryptionAlgorithm: String? = null,
    recipient: RecipientInfo? = null,
    dryRun: Boolean? = null,
    dryRunModifiers: Collection<DryRunModifierType>? = null,
    builder: DecryptRequest.Builder.() -> Unit = {},
): DecryptRequest = decryptRequest {

    ciphertextBlob?.let { ciphertextBlob(it) }
    encryptionContext?.let { encryptionContext(it) }
    grantTokens?.let { grantTokens(it) }
    keyId?.let { keyId(it) }
    encryptionAlgorithm?.let { encryptionAlgorithm(it) }
    recipient?.let { recipient(it) }
    dryRun?.let { dryRun(it) }
    dryRunModifiers?.let { dryRunModifiers(it) }

    builder()
}
