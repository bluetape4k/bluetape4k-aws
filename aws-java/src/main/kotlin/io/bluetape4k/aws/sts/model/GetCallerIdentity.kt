package io.bluetape4k.aws.sts.model

import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest

/**
 * DSL block으로 [GetCallerIdentityRequest]를 생성한다.
 *
 * ## 동작과 계약
 * - [builder] 안에서 추가 설정을 적용할 수 있다.
 * - 이 request는 parameter가 없으므로 대부분의 caller는 empty block을 사용한다.
 *
 * ```kotlin
 * val req = getCallerIdentityRequest {}
 * ```
 */
inline fun getCallerIdentityRequest(
    builder: GetCallerIdentityRequest.Builder.() -> Unit,
): GetCallerIdentityRequest =
    GetCallerIdentityRequest.builder().apply(builder).build()
