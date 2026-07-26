package io.bluetape4k.aws.kms.model

import software.amazon.awssdk.services.kms.model.ListAliasesRequest

/**
 * Creates a [ListAliasesRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [ListAliasesRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = listAliasesRequest {
 *     limit(10)
 * }
 * // request.limit() == 10
 * ```
 */
fun listAliasesRequest(
    builder: ListAliasesRequest.Builder.() -> Unit,
): ListAliasesRequest =
    ListAliasesRequest.builder().apply(builder).build()

/**
 * Creates a [ListAliasesRequest] by specifying primary parameters directly.
 *
 * ## Behavior/Contract
 * - Applies [keyId], [limit], and [marker] to the builder only when they are non-null.
 * - Runs [builder] last.
 *
 * ```kotlin
 * val request = listAliasesRequestOf(
 *     keyId = "key-id",
 *     limit = 20
 * )
 * // request.limit() == 20
 * ```
 */
fun listAliasesRequestOf(
    keyId: String? = null,
    limit: Int? = null,
    marker: String? = null,
    builder: ListAliasesRequest.Builder.() -> Unit = {},
): ListAliasesRequest {

    return listAliasesRequest {
        keyId?.let { keyId(it) }
        limit?.let { limit(it) }
        marker?.let { marker(it) }

        builder()
    }
}
