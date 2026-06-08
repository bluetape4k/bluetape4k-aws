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

object NoopS3AccessGrantsOperations: S3AccessGrantsOperations {
    override suspend fun getDataAccess(request: GetDataAccessRequest): GetDataAccessResponse =
        throw UnsupportedOperationException("NoopS3AccessGrantsOperations does not request data access.")

    override suspend fun listCallerAccessGrants(
        request: ListCallerAccessGrantsRequest,
    ): ListCallerAccessGrantsResponse =
        throw UnsupportedOperationException("NoopS3AccessGrantsOperations does not list caller grants.")

    override suspend fun listAccessGrants(request: ListAccessGrantsRequest): ListAccessGrantsResponse =
        throw UnsupportedOperationException("NoopS3AccessGrantsOperations does not list grants.")

    override suspend fun listAccessGrantsInstances(
        request: ListAccessGrantsInstancesRequest,
    ): ListAccessGrantsInstancesResponse =
        throw UnsupportedOperationException("NoopS3AccessGrantsOperations does not list instances.")

    override suspend fun listAccessGrantsLocations(
        request: ListAccessGrantsLocationsRequest,
    ): ListAccessGrantsLocationsResponse =
        throw UnsupportedOperationException("NoopS3AccessGrantsOperations does not list locations.")
}
