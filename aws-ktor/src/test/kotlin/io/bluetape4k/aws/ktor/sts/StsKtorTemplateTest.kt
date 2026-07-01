package io.bluetape4k.aws.ktor.sts

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sts.StsAsyncClient
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse
import software.amazon.awssdk.services.sts.model.Credentials
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import software.amazon.awssdk.services.sts.model.GetSessionTokenRequest
import software.amazon.awssdk.services.sts.model.GetSessionTokenResponse
import java.time.Instant
import java.util.concurrent.CompletableFuture

class StsKtorTemplateTest {

    @Test
    fun `callerIdentity delegates to STS getCallerIdentity`() = runTest {
        val client = mockk<StsAsyncClient>()
        val request = slot<GetCallerIdentityRequest>()
        val response = GetCallerIdentityResponse.builder()
            .account("123456789012")
            .arn("arn:aws:iam::123456789012:user/debop")
            .userId("user-1")
            .build()

        every { client.getCallerIdentity(capture(request)) } returns CompletableFuture.completedFuture(response)

        val result = template(client).callerIdentity()

        result shouldBeEqualTo response
        request.captured shouldBeEqualTo GetCallerIdentityRequest.builder().build()
    }

    @Test
    fun `assumeRole maps role session and duration`() = runTest {
        val client = mockk<StsAsyncClient>()
        val request = slot<AssumeRoleRequest>()
        val response = AssumeRoleResponse.builder()
            .credentials(credentials())
            .build()

        every { client.assumeRole(capture(request)) } returns CompletableFuture.completedFuture(response)

        val result = template(client).assumeRole(
            StsAssumeRoleRequest(
                roleArn = "arn:aws:iam::123456789012:role/orders",
                sessionName = "orders-api",
                durationSeconds = 1_800,
                externalId = "external-1",
            )
        )

        result shouldBeEqualTo response
        request.captured.roleArn() shouldBeEqualTo "arn:aws:iam::123456789012:role/orders"
        request.captured.roleSessionName() shouldBeEqualTo "orders-api"
        request.captured.durationSeconds() shouldBeEqualTo 1_800
        request.captured.externalId() shouldBeEqualTo "external-1"
    }

    @Test
    fun `sessionToken maps duration and MFA fields`() = runTest {
        val client = mockk<StsAsyncClient>()
        val request = slot<GetSessionTokenRequest>()
        val response = GetSessionTokenResponse.builder()
            .credentials(credentials())
            .build()

        every { client.getSessionToken(capture(request)) } returns CompletableFuture.completedFuture(response)

        val result = template(client).sessionToken(
            StsSessionTokenRequest(
                durationSeconds = 900,
                serialNumber = "arn:aws:iam::123456789012:mfa/debop",
                tokenCode = "123456",
            )
        )

        result shouldBeEqualTo response
        request.captured.durationSeconds() shouldBeEqualTo 900
        request.captured.serialNumber() shouldBeEqualTo "arn:aws:iam::123456789012:mfa/debop"
        request.captured.tokenCode() shouldBeEqualTo "123456"
    }

    @Test
    fun `STS request models validate duration ranges`() {
        assertFailsWith<IllegalArgumentException> {
            StsAssumeRoleRequest(
                roleArn = "arn:aws:iam::123456789012:role/orders",
                sessionName = "orders-api",
                durationSeconds = 899,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StsSessionTokenRequest(durationSeconds = 129_601)
        }
    }

    @Test
    fun `callerIdentity cancels the backing future when coroutine is cancelled`() = runTest {
        val client = mockk<StsAsyncClient>()
        val future = CompletableFuture<GetCallerIdentityResponse>()
        every { client.getCallerIdentity(any<GetCallerIdentityRequest>()) } returns future
        val job = launch {
            template(client).callerIdentity()
        }
        runCurrent()

        job.cancel()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `failed STS future preserves original exception`() = runTest {
        val client = mockk<StsAsyncClient>()
        val failure = SdkClientException.create("boom")
        every { client.getCallerIdentity(any<GetCallerIdentityRequest>()) } returns CompletableFuture.failedFuture(failure)

        val error = assertFailsWith<SdkClientException> {
            template(client).callerIdentity()
        }

        error shouldBeEqualTo failure
    }

    private fun template(client: StsAsyncClient): StsKtorTemplate =
        StsKtorTemplate(client)

    private fun credentials(): Credentials =
        Credentials.builder()
            .accessKeyId("access")
            .secretAccessKey("secret")
            .sessionToken("token")
            .expiration(Instant.parse("2026-07-01T01:00:00Z"))
            .build()
}
