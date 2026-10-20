package io.bluetape4k.aws.ktor.s3.accessgrants

import aws.sdk.kotlin.services.s3control.model.GetDataAccessRequest
import aws.sdk.kotlin.services.s3control.model.GetDataAccessResponse
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsInstancesRequest
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsInstancesResponse
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsLocationsRequest
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsLocationsResponse
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsRequest
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsResponse
import aws.sdk.kotlin.services.s3control.model.ListCallerAccessGrantsRequest
import aws.sdk.kotlin.services.s3control.model.ListCallerAccessGrantsResponse

/**
 * Ktor 애플리케이션을 위한 AWS Kotlin SDK suspend S3 Access Grants 작업입니다.
 *
 * 데이터 접근 자격 증명과 목록 응답을 원본 Kotlin SDK 모델로 반환합니다.
 */
interface S3AccessGrantsKtorOperations {

    /** Access Grant가 적용되는 S3 URI의 임시 데이터 접근 자격 증명을 요청합니다. */
    suspend fun getDataAccess(request: GetDataAccessRequest): GetDataAccessResponse

    /** 호출자가 사용할 수 있는 Access Grant 목록을 조회합니다. */
    suspend fun listCallerAccessGrants(request: ListCallerAccessGrantsRequest): ListCallerAccessGrantsResponse

    /** Access Grants 인스턴스의 Grant 목록을 조회합니다. */
    suspend fun listAccessGrants(request: ListAccessGrantsRequest): ListAccessGrantsResponse

    /** 계정의 Access Grants 인스턴스 목록을 조회합니다. */
    suspend fun listAccessGrantsInstances(
        request: ListAccessGrantsInstancesRequest,
    ): ListAccessGrantsInstancesResponse

    /** 등록된 Access Grants 위치 목록을 조회합니다. */
    suspend fun listAccessGrantsLocations(
        request: ListAccessGrantsLocationsRequest,
    ): ListAccessGrantsLocationsResponse
}
