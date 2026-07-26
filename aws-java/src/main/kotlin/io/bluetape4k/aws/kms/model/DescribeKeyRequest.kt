package io.bluetape4k.aws.kms.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest

/**
 * Creates a [DescribeKeyRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [DescribeKeyRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = describeKey {
 *     keyId("key-id")
 * }
 * // request.keyId() == "key-id"
 * ```
 */
inline fun describeKey(
    builder: DescribeKeyRequest.Builder.() -> Unit,
): DescribeKeyRequest =
    DescribeKeyRequest.builder().apply(builder).build()

/**
 * Creates a [DescribeKeyRequest] by specifying a key ID.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [keyId] is blank.
 * - Calls `grantTokens(*grantTokens)` only when [grantTokens] is not empty.
 * - Runs [builder] last.
 *
 * ```kotlin
 * val request = describeKeyOf(
 *     keyId = "alias/sample",
 *     "grant-token-1"
 * )
 * // request.grantTokens().size == 1
 * ```
 */
fun describeKeyOf(
    keyId: String,
    vararg grantTokens: String = emptyArray(),
    builder: DescribeKeyRequest.Builder.() -> Unit = {},
): DescribeKeyRequest {
    keyId.requireNotBlank("keyId")

    return describeKey {
        keyId(keyId)
        if (grantTokens.isNotEmpty()) {
            grantTokens(*grantTokens)
        }

        builder()
    }
}
