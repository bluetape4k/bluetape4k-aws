package io.bluetape4k.aws.kms.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kms.model.PutKeyPolicyRequest

/**
 * Creates a [PutKeyPolicyRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [PutKeyPolicyRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = putKeyPolicyRequest {
 *     keyId("key-id")
 *     policyName("default")
 * }
 * // request.policyName() == "default"
 * ```
 */
inline fun putKeyPolicyRequest(
    builder: PutKeyPolicyRequest.Builder.() -> Unit,
): PutKeyPolicyRequest =
    PutKeyPolicyRequest.builder().apply(builder).build()

/**
 * Creates a [PutKeyPolicyRequest] by specifying primary parameters directly.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [keyId], [policyName], or [policy] is blank.
 * - When validation passes, sets the three fields on the builder and then runs [builder].
 *
 * ```kotlin
 * val request = putKeyPolicyRequestOf(
 *     keyId = "key-id",
 *     policyName = "default",
 *     policy = """{"Version":"2012-10-17","Statement":[]}"""
 * )
 * // request.keyId() == "key-id"
 * ```
 */
fun putKeyPolicyRequestOf(
    keyId: String,
    policyName: String,
    policy: String,
    builder: PutKeyPolicyRequest.Builder.() -> Unit = {},
): PutKeyPolicyRequest {
    keyId.requireNotBlank("keyId")
    policyName.requireNotBlank("policyName")
    policy.requireNotBlank("policy")

    return putKeyPolicyRequest {
        keyId(keyId)
        policyName(policyName)
        policy(policy)

        builder()
    }
}
