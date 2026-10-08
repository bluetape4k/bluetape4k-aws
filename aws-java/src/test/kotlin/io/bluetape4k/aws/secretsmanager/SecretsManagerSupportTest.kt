package io.bluetape4k.aws.secretsmanager

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.secretsmanager.model.batchGetSecretValueRequestOf
import io.bluetape4k.aws.secretsmanager.model.createSecretRequestOf
import io.bluetape4k.aws.secretsmanager.model.getSecretValueRequestOf
import io.bluetape4k.aws.secretsmanager.model.listSecretsRequestOf
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.BatchGetSecretValueRequest
import software.amazon.awssdk.services.secretsmanager.model.BatchGetSecretValueResponse
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsRequest
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsResponse
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueResponse
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException
import java.net.URI
import java.util.concurrent.CompletableFuture

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecretsManagerSupportTest {

    private companion object: KLogging() {
        private const val SENTINEL = "raw-secret-value"
    }

    private val client = mockk<SecretsManagerClient>()
    private val asyncClient = mockk<SecretsManagerAsyncClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client, asyncClient)
    }

    @Test
    fun `client factories use local endpoint static credentials and explicit region`() {
        val credentialsProvider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("test", "test")
        )

        secretsManagerClientOf(
            endpoint = URI("http://localhost:4566"),
            region = Region.US_EAST_1,
            credentialsProvider = credentialsProvider,
        ).close()

        secretsManagerAsyncClientOf(
            endpoint = URI("http://localhost:4566"),
            region = Region.US_EAST_1,
            credentialsProvider = credentialsProvider,
        ).close()
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
            batchGetSecretValueRequestOf(listOf("secret-1"), nextToken = " ")
        }

        val request = listSecretsRequestOf(maxResults = 10, nextToken = "token-1")

        log.debug { "request=$request" }
        request.maxResults() shouldBeEqualTo 10
        request.nextToken() shouldBeEqualTo "token-1"
    }

    @Test
    fun `get secret string wraps returned plaintext and preserves missing resource exception`() {
        val requestSlot = slot<GetSecretValueRequest>()

        every { client.getSecretValue(capture(requestSlot)) } returns
                GetSecretValueResponse.builder().secretString(SENTINEL).build()

        val secret = client.getSecretString("secret-id")

        log.debug { "secret=$secret" }
        secret.reveal() shouldBeEqualTo SENTINEL
        secret.toString() shouldBeEqualTo AwsSecretValue.REDACTED
        requestSlot.captured.secretId() shouldBeEqualTo "secret-id"

        val missing = ResourceNotFoundException.builder()
            .message("missing secret")
            .build()

        every { client.getSecretValue(any<GetSecretValueRequest>()) } throws missing

        val error = assertFailsWith<ResourceNotFoundException> {
            client.getSecretString("missing-secret")
        }
        error.message shouldContain "missing secret"
    }

    @Test
    fun `get secret string fails safely for binary-only response`() {
        every { client.getSecretValue(any<GetSecretValueRequest>()) } returns
                GetSecretValueResponse.builder()
                    .secretBinary(SdkBytes.fromUtf8String(SENTINEL))
                    .build()

        val error = assertFailsWith<IllegalStateException> {
            client.getSecretString("binary-secret")
        }

        error.message shouldNotContain SENTINEL
    }

    @Test
    fun `create and put secret accept redacted values without diagnostic leakage`() {
        val createSlot = slot<CreateSecretRequest>()
        val putSlot = slot<PutSecretValueRequest>()

        every { client.createSecret(capture(createSlot)) } returns CreateSecretResponse.builder().arn("arn").build()
        every { client.putSecretValue(capture(putSlot)) } returns PutSecretValueResponse.builder().arn("arn").build()

        val secret = awsSecretValueOf(SENTINEL)
        log.debug { "secret=$secret" }

        client.createSecret(name = "secret-name", secretValue = secret, description = "description")
        client.putSecretValue(secretId = "secret-id", secretValue = secret)

        createSlot.captured.secretString() shouldBeEqualTo SENTINEL
        putSlot.captured.secretString() shouldBeEqualTo SENTINEL
        secret.toString() shouldNotContain SENTINEL
    }

    @Test
    fun `batch and list helpers preserve raw response and make one sdk call`() {
        val batchResponse = BatchGetSecretValueResponse.builder().build()
        val listResponse = ListSecretsResponse.builder().nextToken("next").build()

        every { client.batchGetSecretValue(any<BatchGetSecretValueRequest>()) } returns batchResponse
        every { client.listSecrets(any<ListSecretsRequest>()) } returns listResponse

        client.batchGetSecretValues(listOf("secret-a", "secret-b")) shouldBeEqualTo batchResponse
        client.listSecrets(maxResults = 5, nextToken = "token") shouldBeEqualTo listResponse

        verify(exactly = 1) { client.batchGetSecretValue(any<BatchGetSecretValueRequest>()) }
        verify(exactly = 1) { client.listSecrets(any<ListSecretsRequest>()) }
    }

    @Test
    fun `async coroutine adapters await and propagate cancellation`() = runTest {

        every { asyncClient.getSecretValue(any<GetSecretValueRequest>()) } returns
                completableFutureOf(GetSecretValueResponse.builder().secretString(SENTINEL).build())

        asyncClient.getSecretString("secret-id").reveal() shouldBeEqualTo SENTINEL

        val cancelled = CompletableFuture<GetSecretValueResponse>()
        cancelled.completeExceptionally(CancellationException("cancelled"))

        every { asyncClient.getSecretValue(any<GetSecretValueRequest>()) } returns cancelled

        assertFailsWith<CancellationException> {
            asyncClient.getSecretValueAsync("secret-id").await()
        }
    }

    @Test
    fun `create secret request uses redacted input`() {
        val request = createSecretRequestOf(name = "secret-name", secretValue = awsSecretValueOf(SENTINEL))

        log.debug { "request=$request" }
        request.name() shouldBeEqualTo "secret-name"
        request.secretString() shouldBeEqualTo SENTINEL
        request.toString().contains(SENTINEL).shouldBeFalse()
    }
}
