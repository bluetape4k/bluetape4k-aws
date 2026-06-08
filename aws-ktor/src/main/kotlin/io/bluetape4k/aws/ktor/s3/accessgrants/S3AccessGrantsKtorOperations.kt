package io.bluetape4k.aws.ktor.s3.accessgrants

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
 * Coroutine S3 Access Grants operations for Ktor applications.
 *
 * ## Contract
 *
 * This facade exposes the common read and data-access path from the AWS SDK v2
 * S3 Control client without leaking `CompletableFuture` to application code.
 * Administrative create, update, and delete calls remain available through the
 * raw `S3ControlAsyncClient` configured by the application.
 */
interface S3AccessGrantsKtorOperations {

    /**
     * Requests temporary data-access credentials for an S3 URI covered by an Access Grant.
     */
    suspend fun getDataAccess(request: GetDataAccessRequest): GetDataAccessResponse

    /**
     * Lists Access Grants available to the caller.
     */
    suspend fun listCallerAccessGrants(request: ListCallerAccessGrantsRequest): ListCallerAccessGrantsResponse

    /**
     * Lists grants in an Access Grants instance.
     */
    suspend fun listAccessGrants(request: ListAccessGrantsRequest): ListAccessGrantsResponse

    /**
     * Lists Access Grants instances for an account.
     */
    suspend fun listAccessGrantsInstances(
        request: ListAccessGrantsInstancesRequest,
    ): ListAccessGrantsInstancesResponse

    /**
     * Lists registered Access Grants locations.
     */
    suspend fun listAccessGrantsLocations(
        request: ListAccessGrantsLocationsRequest,
    ): ListAccessGrantsLocationsResponse
}
