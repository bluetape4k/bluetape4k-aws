package io.bluetape4k.aws.sts.model

import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest

/**
 * Builds a [GetCallerIdentityRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Additional settings can be applied inside [builder].
 * - Most callers use an empty block because this request has no parameters.
 *
 * ```kotlin
 * val req = getCallerIdentityRequest {}
 * ```
 */
inline fun getCallerIdentityRequest(
    builder: GetCallerIdentityRequest.Builder.() -> Unit,
): GetCallerIdentityRequest =
    GetCallerIdentityRequest.builder().apply(builder).build()
