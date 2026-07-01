package io.bluetape4k.aws.ktor.sts

import software.amazon.awssdk.services.sts.model.AssumeRoleResponse
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import software.amazon.awssdk.services.sts.model.GetSessionTokenResponse

/**
 * Coroutine-oriented STS operations for Ktor applications.
 *
 * ## Contract
 *
 * Methods return raw AWS SDK response objects so callers keep full identity,
 * credential, and session metadata.
 */
interface StsKtorOperations {
    suspend fun callerIdentity(): GetCallerIdentityResponse
    suspend fun assumeRole(request: StsAssumeRoleRequest): AssumeRoleResponse
    suspend fun sessionToken(request: StsSessionTokenRequest = StsSessionTokenRequest()): GetSessionTokenResponse
}
