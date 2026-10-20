package io.bluetape4k.aws.ktor.s3.accessgrants

import aws.sdk.kotlin.services.s3control.S3ControlClient
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

/** AWS Kotlin SDK [S3ControlClient]를 사용하는 기본 [S3AccessGrantsKtorOperations] 구현입니다. */
class S3AccessGrantsKtorTemplate(
    private val s3ControlClient: S3ControlClient,
): S3AccessGrantsKtorOperations {

    override suspend fun getDataAccess(request: GetDataAccessRequest): GetDataAccessResponse =
        s3ControlClient.getDataAccess(request)

    override suspend fun listCallerAccessGrants(
        request: ListCallerAccessGrantsRequest,
    ): ListCallerAccessGrantsResponse =
        s3ControlClient.listCallerAccessGrants(request)

    override suspend fun listAccessGrants(request: ListAccessGrantsRequest): ListAccessGrantsResponse =
        s3ControlClient.listAccessGrants(request)

    override suspend fun listAccessGrantsInstances(
        request: ListAccessGrantsInstancesRequest,
    ): ListAccessGrantsInstancesResponse =
        s3ControlClient.listAccessGrantsInstances(request)

    override suspend fun listAccessGrantsLocations(
        request: ListAccessGrantsLocationsRequest,
    ): ListAccessGrantsLocationsResponse =
        s3ControlClient.listAccessGrantsLocations(request)
}
