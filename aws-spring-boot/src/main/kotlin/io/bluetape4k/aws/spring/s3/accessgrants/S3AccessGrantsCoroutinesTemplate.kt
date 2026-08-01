package io.bluetape4k.aws.spring.s3.accessgrants

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.s3control.S3ControlAsyncClient
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
 * AWS SDK v2 [S3ControlAsyncClient]를 사용하는 코루틴 친화적인 [S3AccessGrantsOperations]입니다.
 */
class S3AccessGrantsCoroutinesTemplate(
    private val s3ControlAsyncClient: S3ControlAsyncClient,
): S3AccessGrantsOperations {

    override suspend fun getDataAccess(request: GetDataAccessRequest): GetDataAccessResponse =
        s3ControlAsyncClient.getDataAccess(request).await()

    override suspend fun listCallerAccessGrants(
        request: ListCallerAccessGrantsRequest,
    ): ListCallerAccessGrantsResponse =
        s3ControlAsyncClient.listCallerAccessGrants(request).await()

    override suspend fun listAccessGrants(request: ListAccessGrantsRequest): ListAccessGrantsResponse =
        s3ControlAsyncClient.listAccessGrants(request).await()

    override suspend fun listAccessGrantsInstances(
        request: ListAccessGrantsInstancesRequest,
    ): ListAccessGrantsInstancesResponse =
        s3ControlAsyncClient.listAccessGrantsInstances(request).await()

    override suspend fun listAccessGrantsLocations(
        request: ListAccessGrantsLocationsRequest,
    ): ListAccessGrantsLocationsResponse =
        s3ControlAsyncClient.listAccessGrantsLocations(request).await()
}
