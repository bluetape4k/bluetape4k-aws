package io.bluetape4k.aws.spring.s3.accessgrants

import software.amazon.awssdk.services.s3control.model.GetDataAccessRequest
import software.amazon.awssdk.services.s3control.model.GetDataAccessResponse
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsInstancesRequest
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsInstancesResponse
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsLocationsRequest
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsLocationsResponse
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsRequest
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsResponse
import software.amazon.awssdk.services.s3control.model.ListCallerAccessGrantsRequest
import software.amazon.awssdk.services.s3control.model.ListCallerAccessGrantsResponse

/**
 * Spring 애플리케이션을 위한 코루틴 기반 S3 Access Grants 작업입니다.
 *
 * ## 계약
 *
 * 이 인터페이스는 애플리케이션 코드에 `CompletableFuture`를 노출하지 않고 AWS SDK v2
 * S3 Control 클라이언트의 공통 읽기 및 데이터 접근 요청 경로를 제공합니다. 관리용 생성,
 * 갱신, 삭제 호출은 원본 `S3ControlClient`와 `S3ControlAsyncClient` Bean에서 계속 사용할 수 있습니다.
 */
interface S3AccessGrantsOperations {

    /**
     * Access Grant가 적용되는 S3 URI의 임시 데이터 접근 자격 증명을 요청합니다.
     */
    suspend fun getDataAccess(request: GetDataAccessRequest): GetDataAccessResponse

    /**
     * 호출자가 사용할 수 있는 Access Grant 목록을 조회합니다.
     */
    suspend fun listCallerAccessGrants(request: ListCallerAccessGrantsRequest): ListCallerAccessGrantsResponse

    /**
     * Access Grants 인스턴스의 Grant 목록을 조회합니다.
     */
    suspend fun listAccessGrants(request: ListAccessGrantsRequest): ListAccessGrantsResponse

    /**
     * 계정의 Access Grants 인스턴스 목록을 조회합니다.
     */
    suspend fun listAccessGrantsInstances(
        request: ListAccessGrantsInstancesRequest,
    ): ListAccessGrantsInstancesResponse

    /**
     * 등록된 Access Grants 위치 목록을 조회합니다.
     */
    suspend fun listAccessGrantsLocations(
        request: ListAccessGrantsLocationsRequest,
    ): ListAccessGrantsLocationsResponse
}
