package io.bluetape4k.aws.sts

import io.bluetape4k.aws.sts.model.assumeRoleRequestOf
import io.bluetape4k.aws.sts.model.getCallerIdentityRequest
import io.bluetape4k.aws.sts.model.getSessionTokenRequest
import software.amazon.awssdk.services.sts.StsAsyncClient
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import software.amazon.awssdk.services.sts.model.GetSessionTokenResponse
import java.util.concurrent.CompletableFuture

/**
 * Returns caller identity information for the current AWS credentials asynchronously.
 *
 * ## Behavior and contract
 * - Returns a response containing account ID, user ID, and ARN information asynchronously.
 *
 * ```kotlin
 * val response = stsAsyncClient.getCallerIdentityAsync().join()
 * // response.account().isNotBlank() == true
 * ```
 */
fun StsAsyncClient.getCallerIdentityAsync(): CompletableFuture<GetCallerIdentityResponse> {
    val request = getCallerIdentityRequest {}
    return getCallerIdentity(request)
}

/**
 * Assumes an IAM role temporarily and returns temporary credentials asynchronously.
 *
 * ## Behavior and contract
 * - [roleArn] is the ARN of the IAM role to assume.
 * - [sessionName] is the session name recorded in audit logs.
 * - [durationSeconds] is the temporary credential lifetime in seconds.
 * - [durationSeconds] must be in the 900..43200 range; out-of-range values throw [IllegalArgumentException].
 *
 * ```kotlin
 * val response = stsAsyncClient.assumeRoleAsync(
 *     roleArn = "arn:aws:iam::123456789012:role/MyRole",
 *     sessionName = "my-session"
 * ).join()
 * // response.credentials().accessKeyId().isNotBlank() == true
 * ```
 */
fun StsAsyncClient.assumeRoleAsync(
    roleArn: String,
    sessionName: String,
    durationSeconds: Int = 3600,
): CompletableFuture<AssumeRoleResponse> {
    requireValidAssumeRoleDuration(durationSeconds)

    val request = assumeRoleRequestOf(roleArn, sessionName) {
        durationSeconds(durationSeconds)
    }
    return assumeRole(request)
}

/**
 * Returns MFA-based temporary session credentials asynchronously.
 *
 * ## Behavior and contract
 * - [durationSeconds] is the temporary credential lifetime in seconds.
 * - [durationSeconds] must be in the 900..129600 range; out-of-range values throw [IllegalArgumentException].
 *
 * ```kotlin
 * val response = stsAsyncClient.getSessionTokenAsync().join()
 * // response.credentials().accessKeyId().isNotBlank() == true
 * ```
 */
fun StsAsyncClient.getSessionTokenAsync(
    durationSeconds: Int = 3600,
): CompletableFuture<GetSessionTokenResponse> {
    requireValidSessionTokenDuration(durationSeconds)

    val request = getSessionTokenRequest {
        durationSeconds(durationSeconds)
    }
    return getSessionToken(request)
}
