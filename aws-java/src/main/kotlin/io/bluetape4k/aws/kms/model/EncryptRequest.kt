package io.bluetape4k.aws.kms.model

import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kms.model.EncryptRequest

/**
 * Creates an [EncryptRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [EncryptRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = encryptRequest {
 *     keyId("key-id")
 * }
 * // request.keyId() == "key-id"
 * ```
 */
inline fun encryptRequest(
    builder: EncryptRequest.Builder.() -> Unit,
): EncryptRequest =
    EncryptRequest.builder().apply(builder).build()

/**
 * Creates an [EncryptRequest] by specifying primary parameters directly.
 *
 * ## Behavior/Contract
 * - Applies each argument to the same-named builder method only when it is non-null.
 * - Runs [builder] last.
 *
 * ```kotlin
 * val request = encryptRequestOf(
 *     keyId = "key-id",
 *     plainText = SdkBytes.fromUtf8String("plain-text")
 * )
 * // request.keyId() == "key-id"
 * ```
 */
inline fun encryptRequestOf(
    keyId: String? = null,
    plainText: SdkBytes? = null,
    encryptionContext: Map<String, String>? = null,
    grantTokens: List<String>? = null,
    encryptionAlgorithm: String? = null,
    dryRun: Boolean? = null,
    builder: EncryptRequest.Builder.() -> Unit = {},
): EncryptRequest = encryptRequest {

    keyId?.let { keyId(it) }
    plainText?.let { plaintext(it) }
    encryptionContext?.let { encryptionContext(it) }
    grantTokens?.let { grantTokens(it) }
    encryptionAlgorithm?.let { encryptionAlgorithm(it) }
    dryRun?.let { dryRun(it) }

    builder()
}
