package io.bluetape4k.aws.spring.s3.accessgrants

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import java.util.concurrent.CompletableFuture

class S3AccessGrantsCoroutinesTemplateTest {

    private val client = mockk<S3ControlAsyncClient>()
    private val template = S3AccessGrantsCoroutinesTemplate(client)

    @BeforeEach
    fun resetMocks() {
        clearMocks(client)
    }

    @Test
    fun `getDataAccess delegates to S3 Control async client`() = runTest {
        val request = GetDataAccessRequest.builder().build()
        val response = GetDataAccessResponse.builder().build()
        every { client.getDataAccess(request) } returns CompletableFuture.completedFuture(response)

        template.getDataAccess(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.getDataAccess(request) }
    }

    @Test
    fun `listCallerAccessGrants delegates to S3 Control async client`() = runTest {
        val request = ListCallerAccessGrantsRequest.builder().build()
        val response = ListCallerAccessGrantsResponse.builder().build()
        every { client.listCallerAccessGrants(request) } returns CompletableFuture.completedFuture(response)

        template.listCallerAccessGrants(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.listCallerAccessGrants(request) }
    }

    @Test
    fun `listAccessGrants delegates to S3 Control async client`() = runTest {
        val request = ListAccessGrantsRequest.builder().build()
        val response = ListAccessGrantsResponse.builder().build()
        every { client.listAccessGrants(request) } returns CompletableFuture.completedFuture(response)

        template.listAccessGrants(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.listAccessGrants(request) }
    }

    @Test
    fun `listAccessGrantsInstances delegates to S3 Control async client`() = runTest {
        val request = ListAccessGrantsInstancesRequest.builder().build()
        val response = ListAccessGrantsInstancesResponse.builder().build()
        every { client.listAccessGrantsInstances(request) } returns CompletableFuture.completedFuture(response)

        template.listAccessGrantsInstances(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.listAccessGrantsInstances(request) }
    }

    @Test
    fun `listAccessGrantsLocations delegates to S3 Control async client`() = runTest {
        val request = ListAccessGrantsLocationsRequest.builder().build()
        val response = ListAccessGrantsLocationsResponse.builder().build()
        every { client.listAccessGrantsLocations(request) } returns CompletableFuture.completedFuture(response)

        template.listAccessGrantsLocations(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.listAccessGrantsLocations(request) }
    }
}
