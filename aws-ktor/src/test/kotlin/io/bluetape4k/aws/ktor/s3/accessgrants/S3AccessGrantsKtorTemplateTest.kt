package io.bluetape4k.aws.ktor.s3.accessgrants

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3AccessGrantsKtorTemplateTest {

    private val client = mockk<S3ControlAsyncClient>()

    @BeforeEach
    fun resetMocks() {
        clearMocks(client)
    }

    @Test
    fun `getDataAccess delegates to S3 Control async client`() = runSuspendIO {
        val request = GetDataAccessRequest.builder().accountId("123456789012").target("s3://bucket/key").build()
        val response = GetDataAccessResponse.builder().build()
        every { client.getDataAccess(request) } returns CompletableFuture.completedFuture(response)

        S3AccessGrantsKtorTemplate(client).getDataAccess(request) shouldBeSameInstanceAs response
    }

    @Test
    fun `listCallerAccessGrants delegates to S3 Control async client`() = runSuspendIO {
        val request = ListCallerAccessGrantsRequest.builder().accountId("123456789012").build()
        val response = ListCallerAccessGrantsResponse.builder().build()
        every { client.listCallerAccessGrants(request) } returns CompletableFuture.completedFuture(response)

        S3AccessGrantsKtorTemplate(client).listCallerAccessGrants(request) shouldBeSameInstanceAs response
    }

    @Test
    fun `listAccessGrants delegates to S3 Control async client`() = runSuspendIO {
        val request = ListAccessGrantsRequest.builder().accountId("123456789012").build()
        val response = ListAccessGrantsResponse.builder().build()
        every { client.listAccessGrants(request) } returns CompletableFuture.completedFuture(response)

        S3AccessGrantsKtorTemplate(client).listAccessGrants(request) shouldBeSameInstanceAs response
    }

    @Test
    fun `listAccessGrantsInstances delegates to S3 Control async client`() = runSuspendIO {
        val request = ListAccessGrantsInstancesRequest.builder().accountId("123456789012").build()
        val response = ListAccessGrantsInstancesResponse.builder().build()
        every { client.listAccessGrantsInstances(request) } returns CompletableFuture.completedFuture(response)

        S3AccessGrantsKtorTemplate(client).listAccessGrantsInstances(request) shouldBeSameInstanceAs response
    }

    @Test
    fun `listAccessGrantsLocations delegates to S3 Control async client`() = runSuspendIO {
        val request = ListAccessGrantsLocationsRequest.builder().accountId("123456789012").build()
        val response = ListAccessGrantsLocationsResponse.builder().build()
        every { client.listAccessGrantsLocations(request) } returns CompletableFuture.completedFuture(response)

        S3AccessGrantsKtorTemplate(client).listAccessGrantsLocations(request) shouldBeSameInstanceAs response
    }

    @Test
    fun `cancelled data access propagates cancellation`() = runSuspendIO {
        val request = GetDataAccessRequest.builder().accountId("123456789012").target("s3://bucket/key").build()
        val future = CompletableFuture<GetDataAccessResponse>()
        future.cancel(true)
        every { client.getDataAccess(request) } returns future

        assertFailsWith<CancellationException> {
            S3AccessGrantsKtorTemplate(client).getDataAccess(request)
        }
    }
}
