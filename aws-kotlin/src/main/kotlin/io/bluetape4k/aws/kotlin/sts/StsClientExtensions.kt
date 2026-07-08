package io.bluetape4k.aws.kotlin.sts

import aws.sdk.kotlin.services.sts.StsClient
import aws.sdk.kotlin.services.sts.getCallerIdentity
import aws.sdk.kotlin.services.sts.getSessionToken
import aws.sdk.kotlin.services.sts.model.AssumeRoleResponse
import aws.sdk.kotlin.services.sts.model.GetCallerIdentityResponse
import aws.sdk.kotlin.services.sts.model.GetSessionTokenResponse
import io.bluetape4k.aws.kotlin.sts.model.assumeRoleRequestOf

/**
 * Returns caller identity details for the current AWS credentials.
 *
 * ## Contract
 * - Returns the account ID, user ID, and ARN associated with the configured credentials.
 *
 * ```kotlin
 * val response = client.getCallerIdentity()
 * // response.account?.isNotBlank() == true
 * ```
 */
suspend fun StsClient.getCallerIdentity(): GetCallerIdentityResponse =
    getCallerIdentity {}

/**
 * Assumes an IAM role and returns temporary credentials.
 *
 * ## Contract
 * - [roleArn] is the ARN of the IAM role to assume.
 * - [sessionName] is recorded in AWS audit logs for the assumed-role session.
 * - [durationSeconds] is the temporary credential lifetime in seconds.
 * - Throws [IllegalArgumentException] when [durationSeconds] is outside the 900..43200 range.
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
 * Returns temporary session credentials for the current AWS principal.
 *
 * ## Contract
 * - [durationSeconds] is the temporary credential lifetime in seconds.
 * - Throws [IllegalArgumentException] when [durationSeconds] is outside the 900..129600 range.
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
