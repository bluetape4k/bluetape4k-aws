package io.bluetape4k.aws.sts.model

import software.amazon.awssdk.services.sts.model.GetSessionTokenRequest

/**
 * DSL block으로 [GetSessionTokenRequest]를 생성한다.
 *
 * ## 동작과 계약
 * - [builder] 안에서 `durationSeconds`, `serialNumber`, `tokenCode`와 다른 field를 설정한다.
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
 * 초 단위 유효 기간으로 [GetSessionTokenRequest]를 생성한다.
 *
 * ## 동작과 계약
 * - [durationSeconds]는 임시 credential 유효 기간이며 단위는 초다. 기본값은 3600초, 즉 1시간이다.
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
