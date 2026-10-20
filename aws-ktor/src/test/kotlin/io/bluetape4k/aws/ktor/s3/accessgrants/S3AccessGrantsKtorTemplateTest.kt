package io.bluetape4k.aws.ktor.s3.accessgrants

import aws.sdk.kotlin.services.s3control.S3ControlClient
import aws.sdk.kotlin.services.s3control.model.Credentials
import aws.sdk.kotlin.services.s3control.model.GetDataAccessRequest
import aws.sdk.kotlin.services.s3control.model.GetDataAccessResponse
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantEntry
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsInstancesRequest
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsInstancesResponse
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsLocationsRequest
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsLocationsResponse
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsRequest
import aws.sdk.kotlin.services.s3control.model.ListAccessGrantsResponse
import aws.sdk.kotlin.services.s3control.model.ListCallerAccessGrantsRequest
import aws.sdk.kotlin.services.s3control.model.ListCallerAccessGrantsResponse
import aws.sdk.kotlin.services.s3control.model.Permission
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test

class S3AccessGrantsKtorTemplateTest {

    private val client = mockk<S3ControlClient>()

    @Test
    fun `getDataAccess preserves temporary credentials and nullable response fields`() = runSuspendIO {
        val request = GetDataAccessRequest { accountId = "123456789012"; target = "s3://bucket/key" }
        val response = GetDataAccessResponse {
            credentials = Credentials {
                accessKeyId = "temporary-access-key"
                secretAccessKey = "temporary-secret-key"
                sessionToken = "temporary-session-token"
            }
            matchedGrantTarget = null
        }
        coEvery { client.getDataAccess(request) } returns response

        val result = S3AccessGrantsKtorTemplate(client).getDataAccess(request)

        result shouldBeSameInstanceAs response
        result.credentials?.accessKeyId shouldBeEqualTo "temporary-access-key"
        result.credentials?.secretAccessKey shouldBeEqualTo "temporary-secret-key"
        result.credentials?.sessionToken shouldBeEqualTo "temporary-session-token"
        result.credentials?.expiration shouldBeEqualTo null
        result.matchedGrantTarget shouldBeEqualTo null
        coVerify(exactly = 1) { client.getDataAccess(request) }
    }

    @Test
    fun `listCallerAccessGrants delegates with pagination and preserves response token`() = runSuspendIO {
        val request = ListCallerAccessGrantsRequest {
            accountId = "123456789012"
            nextToken = "request-page"
        }
        val response = ListCallerAccessGrantsResponse { nextToken = "response-page" }
        val captured = slot<ListCallerAccessGrantsRequest>()
        coEvery { client.listCallerAccessGrants(capture(captured)) } returns response

        val result = S3AccessGrantsKtorTemplate(client).listCallerAccessGrants(request)

        captured.captured.nextToken shouldBeEqualTo "request-page"
        result.nextToken shouldBeEqualTo "response-page"
        coVerify(exactly = 1) { client.listCallerAccessGrants(any<ListCallerAccessGrantsRequest>()) }
    }

    @Test
    fun `listAccessGrants preserves pagination and unknown permission values`() = runSuspendIO {
        val request = ListAccessGrantsRequest {
            accountId = "123456789012"
            nextToken = "request-page"
        }
        val unknownPermission = Permission.SdkUnknown("ADMIN")
        val response = ListAccessGrantsResponse {
            nextToken = "response-page"
            accessGrantsList = listOf(ListAccessGrantEntry { permission = unknownPermission })
        }
        val captured = slot<ListAccessGrantsRequest>()
        coEvery { client.listAccessGrants(capture(captured)) } returns response

        val result = S3AccessGrantsKtorTemplate(client).listAccessGrants(request)

        captured.captured.nextToken shouldBeEqualTo "request-page"
        result.nextToken shouldBeEqualTo "response-page"
        result.accessGrantsList?.single()?.permission shouldBeEqualTo unknownPermission
    }

    @Test
    fun `listAccessGrantsInstances preserves nullable values and pagination`() = runSuspendIO {
        val request = ListAccessGrantsInstancesRequest {
            accountId = "123456789012"
            nextToken = "request-page"
        }
        val response = ListAccessGrantsInstancesResponse { nextToken = "response-page" }
        coEvery { client.listAccessGrantsInstances(request) } returns response

        val result = S3AccessGrantsKtorTemplate(client).listAccessGrantsInstances(request)

        result.nextToken shouldBeEqualTo "response-page"
        coVerify(exactly = 1) { client.listAccessGrantsInstances(request) }
    }

    @Test
    fun `listAccessGrantsLocations preserves pagination`() = runSuspendIO {
        val request = ListAccessGrantsLocationsRequest {
            accountId = "123456789012"
            nextToken = "request-page"
        }
        val response = ListAccessGrantsLocationsResponse { nextToken = "response-page" }
        coEvery { client.listAccessGrantsLocations(request) } returns response

        val result = S3AccessGrantsKtorTemplate(client).listAccessGrantsLocations(request)

        result.nextToken shouldBeEqualTo "response-page"
        coVerify(exactly = 1) { client.listAccessGrantsLocations(request) }
    }
}
