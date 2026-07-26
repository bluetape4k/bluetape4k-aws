package io.bluetape4k.aws.kms.model

import software.amazon.awssdk.services.kms.model.ListKeysRequest

/**
 * Creates a [ListKeysRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [ListKeysRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = listKeysRequest {
 *     limit(10)
 * }
 * // request.limit() == 10
 * ```
 */
inline fun listKeysRequest(
    builder: ListKeysRequest.Builder.() -> Unit,
): ListKeysRequest =
    ListKeysRequest.builder().apply(builder).build()

/**
 * Creates a [ListKeysRequest] by specifying primary parameters directly.
 *
 * ## Behavior/Contract
 * - Applies [limit] and [marker] to the builder only when they are non-null.
 * - Runs [builder] last.
 *
 * ```kotlin
 * val request = listKeysRequestOf(limit = 25)
 * // request.limit() == 25
 * ```
 */
fun listKeysRequestOf(
    limit: Int? = null,
    marker: String? = null,
    builder: ListKeysRequest.Builder.() -> Unit = {},
): ListKeysRequest {

    return listKeysRequest {
        limit?.let { limit(it) }
        marker?.let { marker(it) }

        builder()
    }
}
