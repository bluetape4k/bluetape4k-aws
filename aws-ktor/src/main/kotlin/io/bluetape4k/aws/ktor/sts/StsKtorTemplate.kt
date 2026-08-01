package io.bluetape4k.aws.ktor.sts

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sts.StsAsyncClient
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import software.amazon.awssdk.services.sts.model.GetSessionTokenRequest
import software.amazon.awssdk.services.sts.model.GetSessionTokenResponse

/**
 * [StsAsyncClient]를 사용하는 코루틴 친화적인 [StsKtorOperations] 구현입니다.
 */
class StsKtorTemplate(
    private val stsAsyncClient: StsAsyncClient,
): StsKtorOperations {

    override suspend fun callerIdentity(): GetCallerIdentityResponse =
        stsAsyncClient.getCallerIdentity(GetCallerIdentityRequest.builder().build()).await()

    override suspend fun assumeRole(request: StsAssumeRoleRequest): AssumeRoleResponse =
        stsAsyncClient.assumeRole(
            AssumeRoleRequest.builder()
                .roleArn(request.roleArn)
                .roleSessionName(request.sessionName)
                .durationSeconds(request.durationSeconds)
                .apply {
                    request.externalId?.let(::externalId)
                }
                .build()
        ).await()

    override suspend fun sessionToken(request: StsSessionTokenRequest): GetSessionTokenResponse =
        stsAsyncClient.getSessionToken(
            GetSessionTokenRequest.builder()
                .durationSeconds(request.durationSeconds)
                .apply {
                    request.serialNumber?.let(::serialNumber)
                    request.tokenCode?.let(::tokenCode)
                }
                .build()
        ).await()
}
