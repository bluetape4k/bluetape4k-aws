package io.bluetape4k.aws.sts

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sts.StsAsyncClient
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import software.amazon.awssdk.services.sts.model.GetSessionTokenResponse

/**
 * 현재 AWS credential의 caller identity 정보를 coroutine 결과로 반환한다.
 *
 * ## 동작과 계약
 * - 내부에서 [getCallerIdentityAsync]를 호출한 뒤 `await()`로 완료를 기다린다.
 *
 * ```kotlin
 * val response = stsAsyncClient.getCallerIdentity()
 * // response.account().isNotBlank() == true
 * ```
 */
suspend fun StsAsyncClient.getDefaultCallerIdentity(): GetCallerIdentityResponse =
    getCallerIdentityAsync().await()

/**
 * IAM role을 임시로 assume하고 임시 credential을 coroutine 결과로 반환한다.
 *
 * ## 동작과 계약
 * - 내부에서 [assumeRoleAsync]를 호출한 뒤 `await()`로 완료를 기다린다.
 * - [durationSeconds]는 900..43200 범위여야 하며, 검증 실패 시 [IllegalArgumentException]을 던진다.
 *
 * ```kotlin
 * val response = stsAsyncClient.assumeRole(
 *     roleArn = "arn:aws:iam::123456789012:role/MyRole",
 *     sessionName = "my-session"
 * )
 * // response.credentials().accessKeyId().isNotBlank() == true
 * ```
 */
suspend fun StsAsyncClient.assumeRole(
    roleArn: String,
    sessionName: String,
    durationSeconds: Int = 3600,
): AssumeRoleResponse =
    assumeRoleAsync(roleArn, sessionName, durationSeconds).await()

/**
 * MFA 기반 임시 session credential을 coroutine 결과로 반환한다.
 *
 * ## 동작과 계약
 * - 내부에서 [getSessionTokenAsync]를 호출한 뒤 `await()`로 완료를 기다린다.
 * - [durationSeconds]는 900..129600 범위여야 하며, 검증 실패 시 [IllegalArgumentException]을 던진다.
 *
 * ```kotlin
 * val response = stsAsyncClient.getSessionToken()
 * // response.credentials().accessKeyId().isNotBlank() == true
 * ```
 */
suspend fun StsAsyncClient.getSessionToken(
    durationSeconds: Int = 3600,
): GetSessionTokenResponse =
    getSessionTokenAsync(durationSeconds).await()
