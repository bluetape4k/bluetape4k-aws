package io.bluetape4k.aws.kms.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kms.model.CreateGrantRequest
import software.amazon.awssdk.services.kms.model.GrantOperation

/**
 * Creates a [CreateGrantRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [CreateGrantRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = createGrantRequest {
 *     keyId("key-id")
 *     granteePrincipal("arn:aws:iam::111122223333:role/sample")
 * }
 * // request.keyId() == "key-id"
 * ```
 */
inline fun createGrantRequest(
    builder: CreateGrantRequest.Builder.() -> Unit,
): CreateGrantRequest =
    CreateGrantRequest.builder().apply(builder).build()

/**
 * Creates a [CreateGrantRequest] by specifying primary parameters directly.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [keyId] is blank.
 * - Calls `operations(*operations)` only when [operations] is not empty.
 * - Runs [builder] last.
 *
 * ```kotlin
 * val request = createGrantRequestOf(
 *     keyId = "key-id",
 *     granteePrincipal = "arn:aws:iam::111122223333:role/sample",
 *     GrantOperation.ENCRYPT
 * )
 * // request.operations().size == 1
 * ```
 */
fun createGrantRequestOf(
    keyId: String,
    granteePrincipal: String,
    vararg operations: GrantOperation,
    builder: CreateGrantRequest.Builder.() -> Unit = {},
): CreateGrantRequest {
    keyId.requireNotBlank("keyId")

    return createGrantRequest {
        keyId(keyId)
        granteePrincipal(granteePrincipal)
        if (operations.isNotEmpty()) {
            operations(*operations)
        }

        builder()
    }
}
