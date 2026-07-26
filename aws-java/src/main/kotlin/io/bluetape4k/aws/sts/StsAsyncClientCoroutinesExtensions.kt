package io.bluetape4k.aws.sts

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sts.StsAsyncClient
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import software.amazon.awssdk.services.sts.model.GetSessionTokenResponse

/**
 * Returns caller identity information for the current AWS credentials as a coroutine result.
 *
 * ## Behavior and contract
 * - Calls [getCallerIdentityAsync] internally, then waits for completion with `await()`.
 *
 * ```kotlin
 * val response = stsAsyncClient.getCallerIdentity()
 * // response.account().isNotBlank() == true
 * ```
 */
suspend fun StsAsyncClient.getDefaultCallerIdentity(): GetCallerIdentityResponse =
    getCallerIdentityAsync().await()

/**
 * Assumes an IAM role temporarily and returns temporary credentials as a coroutine result.
 *
 * ## Behavior and contract
 * - Calls [assumeRoleAsync] internally, then waits for completion with `await()`.
 * - [durationSeconds] must be in the 900..43200 range; validation failures throw [IllegalArgumentException].
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
 * Returns MFA-based temporary session credentials as a coroutine result.
 *
 * ## Behavior and contract
 * - Calls [getSessionTokenAsync] internally, then waits for completion with `await()`.
 * - [durationSeconds] must be in the 900..129600 range; validation failures throw [IllegalArgumentException].
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
