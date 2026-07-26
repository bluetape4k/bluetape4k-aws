package io.bluetape4k.aws.kms.model

import software.amazon.awssdk.services.kms.model.CreateKeyRequest
import software.amazon.awssdk.services.kms.model.KeySpec
import software.amazon.awssdk.services.kms.model.KeyUsageType
import software.amazon.awssdk.services.kms.model.Tag

/**
 * Creates a [CreateKeyRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [CreateKeyRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = createKeyRequest {
 *     description("sample key")
 * }
 * // request.description() == "sample key"
 * ```
 */
inline fun createKeyRequest(
    builder: CreateKeyRequest.Builder.() -> Unit,
): CreateKeyRequest =
    CreateKeyRequest.builder().apply(builder).build()

/**
 * Creates a [CreateKeyRequest] by specifying primary parameters directly.
 *
 * ## Behavior/Contract
 * - Applies each argument to the same-named builder method only when it is non-null.
 * - Runs [builder] last.
 *
 * ```kotlin
 * val request = createKeyRequestOf(
 *     description = "application key",
 *     keyUsage = KeyUsageType.ENCRYPT_DECRYPT,
 *     keySpec = KeySpec.SYMMETRIC_DEFAULT
 * )
 * // request.keySpec() == KeySpec.SYMMETRIC_DEFAULT
 * ```
 */
inline fun createKeyRequestOf(
    policy: String? = null,
    description: String? = null,
    keyUsage: KeyUsageType? = null,
    keySpec: KeySpec? = null,
    origin: String? = null,
    customKeyStoreId: String? = null,
    bypassPolicyLockoutSafetyCheck: Boolean? = null,
    tags: List<Tag>? = null,
    multiRegion: Boolean? = null,
    xksKeyId: String? = null,
    builder: CreateKeyRequest.Builder.() -> Unit = {},
): CreateKeyRequest = createKeyRequest {

    policy?.let { policy(it) }
    description?.let { description(it) }
    keyUsage?.let { keyUsage(it) }
    keySpec?.let { keySpec(it) }
    origin?.let { origin(it) }
    customKeyStoreId?.let { customKeyStoreId(it) }
    bypassPolicyLockoutSafetyCheck?.let { bypassPolicyLockoutSafetyCheck(it) }
    tags?.let { tags(it) }
    multiRegion?.let { multiRegion(it) }
    xksKeyId?.let { xksKeyId(it) }

    builder()
}
