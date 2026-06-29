package io.bluetape4k.aws.kotlin.ssm

import aws.sdk.kotlin.services.ssm.SsmClient
import aws.sdk.kotlin.services.ssm.model.GetParameterRequest
import aws.sdk.kotlin.services.ssm.model.GetParameterResponse
import aws.sdk.kotlin.services.ssm.model.GetParametersByPathRequest
import aws.sdk.kotlin.services.ssm.model.GetParametersByPathResponse
import aws.sdk.kotlin.services.ssm.model.GetParametersRequest
import aws.sdk.kotlin.services.ssm.model.GetParametersResponse
import aws.sdk.kotlin.services.ssm.model.Parameter
import aws.sdk.kotlin.services.ssm.model.ParameterNotFound
import aws.sdk.kotlin.services.ssm.model.ParameterType
import aws.sdk.kotlin.services.ssm.model.PutParameterResponse
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.aws.kotlin.auth.LocalCredentialsProvider
import io.bluetape4k.aws.kotlin.secretsmanager.awsSecretValueOf
import io.bluetape4k.aws.kotlin.ssm.model.getParameterRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.getParametersByPathRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.getParametersRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.putSecureParameterRequestOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SsmClientSupportTest {

    @Test
    fun `client factories use local endpoint static credentials and explicit region`() = runTest {
        ssmClientOf(
            endpointUrl = Url.parse("http://localhost:4566"),
            region = "us-east-1",
            credentialsProvider = LocalCredentialsProvider,
        ).close()

        assertFailsWith<IllegalStateException> {
            withSsmClient(
                endpointUrl = Url.parse("http://localhost:4566"),
                region = "us-east-1",
                credentialsProvider = LocalCredentialsProvider,
            ) {
                error("boom")
            }
        }
    }

    @Test
    fun `request builders validate names paths tokens and limits`() {
        assertFailsWith<IllegalArgumentException> {
            getParameterRequestOf(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            getParametersRequestOf((1..11).map { "/app/$it" })
        }
        assertFailsWith<IllegalArgumentException> {
            getParametersByPathRequestOf(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            getParametersByPathRequestOf("/app", nextToken = " ")
        }

        val request = getParametersByPathRequestOf(path = "/app", maxResults = 5, nextToken = "token")

        request.path shouldBeEqualTo "/app"
        request.maxResults shouldBeEqualTo 5
        request.nextToken shouldBeEqualTo "token"
    }

    @Test
    fun `secure and non secure reads map withDecryption and propagate missing exceptions`() = runTest {
        val client = mockk<SsmClient>()
        coEvery { client.getParameter(any<GetParameterRequest>()) } returns GetParameterResponse {
            parameter = Parameter {
                name = "/app/secret"
                value = SENTINEL
                type = ParameterType.SecureString
            }
        }

        val secret = client.getSecureParameter("/app/secret")

        secret.reveal() shouldBeEqualTo SENTINEL

        coEvery { client.getParameter(any<GetParameterRequest>()) } returns GetParameterResponse {
            parameter = Parameter {
                name = "/app/plain"
                value = "plain"
                type = ParameterType.String
            }
        }

        client.getParameter("/app/plain").parameter?.value shouldBeEqualTo "plain"

        val missing = ParameterNotFound { message = "missing parameter" }
        coEvery { client.getParameter(any<GetParameterRequest>()) } throws missing

        val error = assertFailsWith<ParameterNotFound> {
            client.getSecureParameter("/app/missing")
        }
        error.message.orEmpty().contains("missing parameter").shouldBeEqualTo(true)
    }

    @Test
    fun `secure writes require redacted value and collection helpers preserve raw responses`() = runTest {
        val client = mockk<SsmClient>()
        val secret = awsSecretValueOf(SENTINEL)
        val parametersResponse = GetParametersResponse { invalidParameters = listOf("/app/missing") }
        val pathResponse = GetParametersByPathResponse { nextToken = "next" }
        coEvery { client.putParameter(any()) } returns PutParameterResponse { version = 1 }
        coEvery { client.getParameters(any<GetParametersRequest>()) } returns parametersResponse
        coEvery { client.getParametersByPath(any<GetParametersByPathRequest>()) } returns pathResponse

        client.putSecureParameter(name = "/app/secret", value = secret, overwrite = true)
        client.getParameters(listOf("/app/a")) shouldBeEqualTo parametersResponse
        client.getParametersByPath(path = "/app", maxResults = 5, nextToken = "token") shouldBeEqualTo pathResponse

        secret.toString().contains(SENTINEL).shouldBeFalse()
        coVerify(exactly = 1) { client.putParameter(any()) }
        coVerify(exactly = 1) { client.getParameters(any<GetParametersRequest>()) }
        coVerify(exactly = 1) { client.getParametersByPath(any<GetParametersByPathRequest>()) }
    }

    @Test
    fun `put secure parameter request uses redacted input`() {
        val request = putSecureParameterRequestOf(
            name = "/app/secret",
            value = awsSecretValueOf(SENTINEL),
            overwrite = true,
        )

        request.name shouldBeEqualTo "/app/secret"
        request.value shouldBeEqualTo SENTINEL
        request.type shouldBeEqualTo ParameterType.SecureString
        request.toString().contains(SENTINEL).shouldBeFalse()
    }

    @Test
    fun `helpers rethrow cancellation`() = runTest {
        val client = mockk<SsmClient>()
        coEvery { client.getParameter(any<GetParameterRequest>()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            client.getSecureParameter("/app/secret")
        }
    }

    private companion object {
        private const val SENTINEL = "raw-secret-value"
    }
}
