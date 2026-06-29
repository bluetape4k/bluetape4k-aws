package io.bluetape4k.aws.kotlin.secretsmanager

import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.sdk.kotlin.services.secretsmanager.model.BatchGetSecretValueResponse
import aws.sdk.kotlin.services.secretsmanager.model.CreateSecretResponse
import aws.sdk.kotlin.services.secretsmanager.model.GetSecretValueRequest
import aws.sdk.kotlin.services.secretsmanager.model.GetSecretValueResponse
import aws.sdk.kotlin.services.secretsmanager.model.ListSecretsRequest
import aws.sdk.kotlin.services.secretsmanager.model.ListSecretsResponse
import aws.sdk.kotlin.services.secretsmanager.model.PutSecretValueResponse
import aws.sdk.kotlin.services.secretsmanager.model.ResourceNotFoundException
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.aws.kotlin.auth.LocalCredentialsProvider
import io.bluetape4k.aws.kotlin.secretsmanager.model.batchGetSecretValueRequestOf
import io.bluetape4k.aws.kotlin.secretsmanager.model.getSecretValueRequestOf
import io.bluetape4k.aws.kotlin.secretsmanager.model.listSecretsRequestOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecretsManagerClientSupportTest {

    @Test
    fun `client factories use local endpoint static credentials and explicit region`() = runTest {
        secretsManagerClientOf(
            endpointUrl = Url.parse("http://localhost:4566"),
            region = "us-east-1",
            credentialsProvider = LocalCredentialsProvider,
        ).close()

        assertFailsWith<IllegalStateException> {
            withSecretsManagerClient(
                endpointUrl = Url.parse("http://localhost:4566"),
                region = "us-east-1",
                credentialsProvider = LocalCredentialsProvider,
            ) {
                error("boom")
            }
        }
    }

    @Test
    fun `request builders validate identifiers limits and tokens`() {
        assertFailsWith<IllegalArgumentException> {
            getSecretValueRequestOf(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            batchGetSecretValueRequestOf((1..21).map { "secret-$it" })
        }
        assertFailsWith<IllegalArgumentException> {
            listSecretsRequestOf(nextToken = " ")
        }

        val request = listSecretsRequestOf(maxResults = 10, nextToken = "token-1")

        request.maxResults shouldBeEqualTo 10
        request.nextToken shouldBeEqualTo "token-1"
    }

    @Test
    fun `helpers wrap secret string preserve raw batch response and propagate missing exceptions`() = runTest {
        val client = mockk<SecretsManagerClient>()
        coEvery { client.getSecretValue(any<GetSecretValueRequest>()) } returns GetSecretValueResponse {
            secretString = SENTINEL
        }

        val secret = client.getSecretString("secret-id")

        secret.reveal() shouldBeEqualTo SENTINEL
        secret.toString() shouldBeEqualTo AwsSecretValue.REDACTED
        coVerify(exactly = 1) { client.getSecretValue(any<GetSecretValueRequest>()) }

        val batchResponse = BatchGetSecretValueResponse {}
        coEvery { client.batchGetSecretValue(any()) } returns batchResponse

        client.batchGetSecretValues(listOf("secret-a")) shouldBeEqualTo batchResponse

        val missing = ResourceNotFoundException { message = "missing secret" }
        coEvery { client.getSecretValue(any<GetSecretValueRequest>()) } throws missing

        val error = assertFailsWith<ResourceNotFoundException> {
            client.getSecretString("missing-secret")
        }
        error.message.orEmpty().contains("missing secret").shouldBeEqualTo(true)
    }

    @Test
    fun `single page helpers make one sdk call and preserve next token`() = runTest {
        val client = mockk<SecretsManagerClient>()
        val response = ListSecretsResponse { nextToken = "next" }
        coEvery { client.listSecrets(any<ListSecretsRequest>()) } returns response

        client.listSecrets(maxResults = 5, nextToken = "token") shouldBeEqualTo response

        coVerify(exactly = 1) { client.listSecrets(any<ListSecretsRequest>()) }
    }

    @Test
    fun `create and put helpers accept redacted values and do not leak diagnostics`() = runTest {
        val client = mockk<SecretsManagerClient>()
        val secret = awsSecretValueOf(SENTINEL)
        coEvery { client.createSecret(any()) } returns CreateSecretResponse { arn = "arn" }
        coEvery { client.putSecretValue(any()) } returns PutSecretValueResponse { arn = "arn" }

        client.createSecret(name = "secret-name", secretValue = secret)
        client.putSecretValue(secretId = "secret-id", secretValue = secret)

        secret.toString().contains(SENTINEL).shouldBeFalse()
        coVerify(exactly = 1) { client.createSecret(any()) }
        coVerify(exactly = 1) { client.putSecretValue(any()) }
    }

    @Test
    fun `helpers rethrow cancellation`() = runTest {
        val client = mockk<SecretsManagerClient>()
        coEvery { client.getSecretValue(any<GetSecretValueRequest>()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            client.getSecretString("secret-id")
        }
    }

    private companion object {
        private const val SENTINEL = "raw-secret-value"
    }
}
