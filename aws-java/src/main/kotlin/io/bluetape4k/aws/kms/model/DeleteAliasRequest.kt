package io.bluetape4k.aws.kms.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kms.model.DeleteAliasRequest

/**
 * Creates a [DeleteAliasRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [DeleteAliasRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = deleteAlias {
 *     aliasName("alias/sample")
 * }
 * // request.aliasName() == "alias/sample"
 * ```
 */
inline fun deleteAlias(
    builder: DeleteAliasRequest.Builder.() -> Unit,
): DeleteAliasRequest =
    DeleteAliasRequest.builder().apply(builder).build()

/**
 * Creates a [DeleteAliasRequest] by specifying an alias name.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [aliasName] is blank.
 * - When validation passes, sets the value on [DeleteAliasRequest.Builder.aliasName].
 *
 * ```kotlin
 * val request = deleteAliasOf("alias/sample")
 * // request.aliasName() == "alias/sample"
 * ```
 */
fun deleteAliasOf(aliasName: String): DeleteAliasRequest {
    aliasName.requireNotBlank("aliasName")
    return deleteAlias {
        aliasName(aliasName)
    }
}
