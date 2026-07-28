package io.bluetape4k.aws.sts

import io.bluetape4k.aws.sts.model.assumeRoleRequestOf
import io.bluetape4k.aws.sts.model.getCallerIdentityRequest
import io.bluetape4k.aws.sts.model.getSessionTokenRequest
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import software.amazon.awssdk.services.sts.model.GetSessionTokenResponse

/**
 * 현재 AWS credential의 caller identity 정보를 반환한다.
 *
 * ## 동작과 계약
 * - account ID, user ID, ARN 정보를 담은 response를 반환한다.
 *
 * ```kotlin
 * val response = stsClient.getDefaultCallerIdentity()
 * // response.account().isNotBlank() == true
 * ```
 */
fun StsClient.getDefaultCallerIdentity(): GetCallerIdentityResponse {
    val request = getCallerIdentityRequest {}
    return getCallerIdentity(request)
}

/**
 * IAM role을 임시로 assume하고 임시 credential을 반환한다.
 *
 * ## 동작과 계약
 * - [roleArn]은 assume할 IAM role ARN이다.
 * - [sessionName]은 audit log에 기록되는 session name이다.
 * - [durationSeconds]는 임시 credential 유효 기간이며 단위는 초다.
 * - [durationSeconds]는 900..43200 범위여야 하며, 범위를 벗어나면 [IllegalArgumentException]을 던진다.
 *
 * ```kotlin
 * val response = stsClient.assumeRole(
 *     roleArn = "arn:aws:iam::123456789012:role/MyRole",
 *     sessionName = "my-session"
 * )
 * // response.credentials().accessKeyId().isNotBlank() == true
 * ```
 */
fun StsClient.assumeRole(
    roleArn: String,
    sessionName: String,
    durationSeconds: Int = 3600,
): AssumeRoleResponse {
    requireValidAssumeRoleDuration(durationSeconds)

    val request = assumeRoleRequestOf(roleArn, sessionName) {
        durationSeconds(durationSeconds)
    }
    return assumeRole(request)
}

/**
 * MFA 기반 임시 session credential을 반환한다.
 *
 * ## 동작과 계약
 * - [durationSeconds]는 임시 credential 유효 기간이며 단위는 초다.
 * - [durationSeconds]는 900..129600 범위여야 하며, 범위를 벗어나면 [IllegalArgumentException]을 던진다.
 *
 * ```kotlin
 * val response = stsClient.getSessionToken()
 * // response.credentials().accessKeyId().isNotBlank() == true
 * ```
 */
fun StsClient.getSessionToken(
    durationSeconds: Int = 3600,
): GetSessionTokenResponse {
    requireValidSessionTokenDuration(durationSeconds)

    val request = getSessionTokenRequest {
        durationSeconds(durationSeconds)
    }
    return getSessionToken(request)
}
