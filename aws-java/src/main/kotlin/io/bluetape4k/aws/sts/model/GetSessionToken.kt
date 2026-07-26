package io.bluetape4k.aws.sts.model

import software.amazon.awssdk.services.sts.model.GetSessionTokenRequest

/**
 * Builds a [GetSessionTokenRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Set `durationSeconds`, `serialNumber`, `tokenCode`, and other fields inside [builder].
 *
 * ```kotlin
 * val req = getSessionTokenRequest {
 *     durationSeconds(3600)
 * }
 * ```
 */
inline fun getSessionTokenRequest(
    builder: GetSessionTokenRequest.Builder.() -> Unit,
): GetSessionTokenRequest =
    GetSessionTokenRequest.builder().apply(builder).build()

/**
 * Creates a [GetSessionTokenRequest] from a lifetime in seconds.
 *
 * ## Behavior and contract
 * - [durationSeconds] is the temporary credential lifetime in seconds. The default is 3600 seconds, or one hour.
 *
 * ```kotlin
 * val req = getSessionTokenRequestOf(durationSeconds = 7200)
 * // req.durationSeconds() == 7200
 * ```
 */
inline fun getSessionTokenRequestOf(
    durationSeconds: Int = 3600,
    builder: GetSessionTokenRequest.Builder.() -> Unit = {},
): GetSessionTokenRequest =
    getSessionTokenRequest {
        durationSeconds(durationSeconds)

        builder()
    }
