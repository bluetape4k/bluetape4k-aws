package io.bluetape4k.aws.ktor.sts

import aws.sdk.kotlin.services.sts.StsClient
import aws.sdk.kotlin.services.sts.model.AssumeRoleRequest
import aws.sdk.kotlin.services.sts.model.AssumeRoleResponse
import aws.sdk.kotlin.services.sts.model.GetCallerIdentityRequest
import aws.sdk.kotlin.services.sts.model.GetCallerIdentityResponse
import aws.sdk.kotlin.services.sts.model.GetSessionTokenRequest
import aws.sdk.kotlin.services.sts.model.GetSessionTokenResponse

/**
 * [StsClient]를 사용하는 코루틴 중심 [StsKtorOperations] 구현입니다.
 */
class StsKtorTemplate(
    private val stsClient: StsClient,
): StsKtorOperations {

    override suspend fun callerIdentity(): GetCallerIdentityResponse =
        stsClient.getCallerIdentity(GetCallerIdentityRequest {})

    override suspend fun assumeRole(request: StsAssumeRoleRequest): AssumeRoleResponse =
        stsClient.assumeRole(
            AssumeRoleRequest {
                roleArn = request.roleArn
                roleSessionName = request.sessionName
                durationSeconds = request.durationSeconds
                externalId = request.externalId
            }
        )

    override suspend fun sessionToken(request: StsSessionTokenRequest): GetSessionTokenResponse =
        stsClient.getSessionToken(
            GetSessionTokenRequest {
                durationSeconds = request.durationSeconds
                serialNumber = request.serialNumber
                tokenCode = request.tokenCode
            }
        )
}
