package io.bluetape4k.aws.kms.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kms.model.CreateAliasRequest

/**
 * Creates a [CreateAliasRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [CreateAliasRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = createAliasRequest {
 *     aliasName("alias/sample")
 *     targetKeyId("key-id")
 * }
 * // request.aliasName() == "alias/sample"
 * ```
 */
inline fun createAliasRequest(
    builder: CreateAliasRequest.Builder.() -> Unit,
): CreateAliasRequest =
    CreateAliasRequest.builder().apply(builder).build()

/**
 * Creates a [CreateAliasRequest] from an alias name and target key ID.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [aliasName] or [targetKeyId] is blank.
 * - When validation passes, sets both fields and then runs [builder].
 *
 * ```kotlin
 * val request = createAliasRequestOf(
 *     aliasName = "alias/sample",
 *     targetKeyId = "key-id"
 * )
 * // request.targetKeyId() == "key-id"
 * ```
 */
fun createAliasRequestOf(
    aliasName: String,
    targetKeyId: String,
    builder: CreateAliasRequest.Builder.() -> Unit = {},
): CreateAliasRequest {
    aliasName.requireNotBlank("aliasName")
    targetKeyId.requireNotBlank("targetKeyId")

    return createAliasRequest {
        aliasName(aliasName)
        targetKeyId(targetKeyId)

        builder()
    }
}
