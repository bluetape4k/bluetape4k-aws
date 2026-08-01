package io.bluetape4k.aws.ktor.sts

import software.amazon.awssdk.services.sts.model.AssumeRoleResponse
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import software.amazon.awssdk.services.sts.model.GetSessionTokenResponse

/**
 * Ktor 애플리케이션을 위한 코루틴 중심 STS 작업입니다.
 *
 * ## 계약
 *
 * 호출자가 전체 자격, 자격 증명, 세션 메타데이터를 유지할 수 있도록 메서드는 AWS SDK 원본 응답 객체를 반환합니다.
 */
interface StsKtorOperations {
    suspend fun callerIdentity(): GetCallerIdentityResponse
    suspend fun assumeRole(request: StsAssumeRoleRequest): AssumeRoleResponse
    suspend fun sessionToken(request: StsSessionTokenRequest = StsSessionTokenRequest()): GetSessionTokenResponse
}
