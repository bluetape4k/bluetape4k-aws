package io.bluetape4k.aws.ktor.sts

import aws.sdk.kotlin.services.sts.StsClient
import aws.sdk.kotlin.services.sts.model.AssumeRoleRequest
import aws.sdk.kotlin.services.sts.model.AssumeRoleResponse
import aws.sdk.kotlin.services.sts.model.Credentials
import aws.sdk.kotlin.services.sts.model.GetCallerIdentityRequest
import aws.sdk.kotlin.services.sts.model.GetCallerIdentityResponse
import aws.sdk.kotlin.services.sts.model.GetSessionTokenRequest
import aws.sdk.kotlin.services.sts.model.GetSessionTokenResponse
import aws.smithy.kotlin.runtime.time.Instant
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StsKtorTemplateTest {

    private val client = mockk<StsClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `callerIdentity delegates to Kotlin STS client`() = runTest {
        val request = slot<GetCallerIdentityRequest>()
        val response = GetCallerIdentityResponse {
            account = "123456789012"
            arn = "arn:aws:iam::123456789012:user/debop"
            userId = "user-1"
        }
        coEvery { client.getCallerIdentity(capture(request)) } returns response

        val result = template(client).callerIdentity()

        result shouldBeSameInstanceAs response
        result.account shouldBeEqualTo "123456789012"
        request.captured shouldBeEqualTo GetCallerIdentityRequest {}
    }

    @Test
    fun `assumeRole maps role session duration and external ID`() = runTest {
        val request = slot<AssumeRoleRequest>()
        val response = AssumeRoleResponse { credentials = credentials() }
        coEvery { client.assumeRole(capture(request)) } returns response

        val result = template(client).assumeRole(
            StsAssumeRoleRequest(
                roleArn = "arn:aws:iam::123456789012:role/orders",
                sessionName = "orders-api",
                durationSeconds = 1_800,
                externalId = "external-1",
            )
        )

        result shouldBeSameInstanceAs response
        request.captured.roleArn shouldBeEqualTo "arn:aws:iam::123456789012:role/orders"
        request.captured.roleSessionName shouldBeEqualTo "orders-api"
        request.captured.durationSeconds shouldBeEqualTo 1_800
        request.captured.externalId shouldBeEqualTo "external-1"
    }

    @Test
    fun `sessionToken maps duration and MFA fields`() = runTest {
        val request = slot<GetSessionTokenRequest>()
        val response = GetSessionTokenResponse { credentials = credentials() }
        coEvery { client.getSessionToken(capture(request)) } returns response

        val result = template(client).sessionToken(
            StsSessionTokenRequest(
                durationSeconds = 900,
                serialNumber = "arn:aws:iam::123456789012:mfa/debop",
                tokenCode = "123456",
            )
        )

        result shouldBeSameInstanceAs response
        request.captured.durationSeconds shouldBeEqualTo 900
        request.captured.serialNumber shouldBeEqualTo "arn:aws:iam::123456789012:mfa/debop"
        request.captured.tokenCode shouldBeEqualTo "123456"
    }

    @Test
    fun `STS request models validate duration ranges`() {
        StsAssumeRoleRequest("arn:aws:iam::123456789012:role/orders", "orders-api", durationSeconds = 43_200)
        StsSessionTokenRequest(durationSeconds = 129_600)

        assertFailsWith<IllegalArgumentException> {
            StsAssumeRoleRequest("arn:aws:iam::123456789012:role/orders", "orders-api", durationSeconds = 899)
        }
        assertFailsWith<IllegalArgumentException> {
            StsAssumeRoleRequest("arn:aws:iam::123456789012:role/orders", "orders-api", durationSeconds = 43_201)
        }
        assertFailsWith<IllegalArgumentException> { StsSessionTokenRequest(durationSeconds = 129_601) }
    }

    @Test
    fun `STS response models retain credentials without logging them`() = runTest {
        val response = AssumeRoleResponse { credentials = credentials() }
        coEvery { client.assumeRole(any<AssumeRoleRequest>()) } returns response

        val result = template(client).assumeRole(
            StsAssumeRoleRequest("arn:aws:iam::123456789012:role/orders", "orders-api")
        )

        result.credentials?.accessKeyId shouldBeEqualTo "access-secret"
        result.credentials?.secretAccessKey shouldBeEqualTo "secret-secret"
        result.credentials?.sessionToken shouldBeEqualTo "session-secret"
    }

    @Test
    fun `callerIdentity cancellation propagates to the suspend client call`() = runTest {
        coEvery { client.getCallerIdentity(any<GetCallerIdentityRequest>()) } coAnswers { awaitCancellation() }
        val job = launch { template(client).callerIdentity() }
        runCurrent()
        job.cancel()
        job.isCancelled.shouldBeTrue()
    }

    @Test
    fun `failed STS call preserves original exception`() = runTest {
        val failure = IllegalStateException("boom")
        coEvery { client.getCallerIdentity(any<GetCallerIdentityRequest>()) } throws failure

        val error = assertFailsWith<IllegalStateException> { template(client).callerIdentity() }
        error shouldBeSameInstanceAs failure
    }

    private fun template(client: StsClient): StsKtorTemplate = StsKtorTemplate(client)

    private fun credentials(): Credentials = Credentials {
        accessKeyId = "access-secret"
        secretAccessKey = "secret-secret"
        sessionToken = "session-secret"
        expiration = Instant.fromEpochSeconds(1_800_000_000L)
    }
}
