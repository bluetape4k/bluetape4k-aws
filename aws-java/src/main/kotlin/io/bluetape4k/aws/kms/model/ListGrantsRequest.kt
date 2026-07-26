package io.bluetape4k.aws.kms.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kms.model.ListGrantsRequest

/**
 * Creates a [ListGrantsRequest] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [ListGrantsRequest.builder], then calls `build()`.
 *
 * ```kotlin
 * val request = listGrantsRequest {
 *     keyId("key-id")
 *     limit(10)
 * }
 * // request.limit() == 10
 * ```
 */
inline fun listGrantsRequest(
    builder: ListGrantsRequest.Builder.() -> Unit,
): ListGrantsRequest =
    ListGrantsRequest.builder().apply(builder).build()

/**
 * Creates a [ListGrantsRequest] by specifying primary parameters directly.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [keyId] is blank.
 * - Applies [grantId], [marker], and [limit] to the builder only when they are non-null.
 * - Runs [builder] last.
 *
 * ```kotlin
 * val request = listGrantsRequestOf(
 *     keyId = "key-id",
 *     limit = 20
 * )
 * // request.keyId() == "key-id"
 * ```
 */
fun listGrantsRequestOf(
    keyId: String,
    grantId: String? = null,
    marker: String? = null,
    limit: Int? = null,
    builder: ListGrantsRequest.Builder.() -> Unit = {},
): ListGrantsRequest {
    keyId.requireNotBlank("keyId")

    return listGrantsRequest {
        keyId(keyId)
        grantId?.let { grantId(it) }
        marker?.let { marker(it) }
        limit?.let { limit(it) }

        builder()
    }
}
