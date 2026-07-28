package io.bluetape4k.aws.kotlin.sts

import aws.sdk.kotlin.services.sts.StsClient
import aws.sdk.kotlin.services.sts.getCallerIdentity
import aws.sdk.kotlin.services.sts.getSessionToken
import aws.sdk.kotlin.services.sts.model.AssumeRoleResponse
import aws.sdk.kotlin.services.sts.model.GetCallerIdentityResponse
import aws.sdk.kotlin.services.sts.model.GetSessionTokenResponse
import io.bluetape4k.aws.kotlin.sts.model.assumeRoleRequestOf

/**
 * 현재 AWS credential에 연결된 caller identity 정보를 조회한다.
 *
 * ## 계약
 * - 설정된 credential에 연결된 account ID, user ID, ARN을 반환한다.
 *
 * ```kotlin
 * val response = client.getCallerIdentity()
 * // response.account?.isNotBlank() == true
 * ```
 */
suspend fun StsClient.getCallerIdentity(): GetCallerIdentityResponse =
    getCallerIdentity {}

/**
 * IAM role을 assume하고 임시 credential을 반환한다.
 *
 * ## 계약
 * - [roleArn]은 assume할 IAM role ARN이다.
 * - [sessionName]은 assumed-role session의 AWS audit log에 기록되는 이름이다.
 * - [durationSeconds]는 임시 credential 유효 기간이며 단위는 초다.
 * - [durationSeconds]가 900..43200 범위를 벗어나면 [IllegalArgumentException]을 던진다.
 *
 * ```kotlin
 * val response = client.assumeRole(
 *     roleArn = "arn:aws:iam::123456789012:role/MyRole",
 *     sessionName = "my-session"
 * )
 * // response.credentials?.accessKeyId?.isNotBlank() == true
 * ```
 */
suspend fun StsClient.assumeRole(
    roleArn: String,
    sessionName: String,
    durationSeconds: Int = 3600,
): AssumeRoleResponse {
    requireValidAssumeRoleDuration(durationSeconds)

    val request = assumeRoleRequestOf(roleArn, sessionName) {
        this.durationSeconds = durationSeconds
    }
    return assumeRole(request)
}

/**
 * 현재 AWS principal에 대한 임시 session credential을 반환한다.
 *
 * ## 계약
 * - [durationSeconds]는 임시 credential 유효 기간이며 단위는 초다.
 * - [durationSeconds]가 900..129600 범위를 벗어나면 [IllegalArgumentException]을 던진다.
 *
 * ```kotlin
 * val response = client.getSessionToken()
 * // response.credentials?.accessKeyId?.isNotBlank() == true
 * ```
 */
suspend fun StsClient.getSessionToken(
    durationSeconds: Int = 3600,
): GetSessionTokenResponse {
    requireValidSessionTokenDuration(durationSeconds)
    return getSessionToken {
        this.durationSeconds = durationSeconds
    }
}
