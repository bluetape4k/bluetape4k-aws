package io.bluetape4k.aws.ssm

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.auth.awsBasicCredentialsOf
import io.bluetape4k.aws.secretsmanager.awsSecretValueOf
import io.bluetape4k.aws.ssm.model.getParameterRequestOf
import io.bluetape4k.aws.ssm.model.getParametersByPathRequestOf
import io.bluetape4k.aws.ssm.model.getParametersRequestOf
import io.bluetape4k.aws.ssm.model.putSecureParameterRequestOf
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
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmAsyncClient
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import software.amazon.awssdk.services.ssm.model.GetParameterResponse
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse
import software.amazon.awssdk.services.ssm.model.GetParametersRequest
import software.amazon.awssdk.services.ssm.model.GetParametersResponse
import software.amazon.awssdk.services.ssm.model.Parameter
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException
import software.amazon.awssdk.services.ssm.model.ParameterType
import software.amazon.awssdk.services.ssm.model.PutParameterRequest
import software.amazon.awssdk.services.ssm.model.PutParameterResponse
import java.net.URI
import java.util.concurrent.CompletableFuture

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SsmSupportTest {

    private companion object: KLogging() {
        private const val SENTINEL = "raw-secret-value"
    }

    private val client = mockk<SsmClient>(relaxed = true)
    private val asyncClient = mockk<SsmAsyncClient>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(client, asyncClient)
    }

    @Test
    fun `client factories use local endpoint static credentials and explicit region`() {
        val credentialsProvider = StaticCredentialsProvider.create(awsBasicCredentialsOf("test", "test"))

        ssmClientOf(
            endpoint = URI("http://localhost:4566"),
            region = Region.US_EAST_1,
            credentialsProvider = credentialsProvider,
        ).close()

        ssmAsyncClientOf(
            endpoint = URI("http://localhost:4566"),
            region = Region.US_EAST_1,
            credentialsProvider = credentialsProvider,
        ).close()
    }

    @Test
    fun `request builders validate names paths tokens and batch limits`() {
        assertFailsWith<IllegalArgumentException> {
            getParameterRequestOf(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            getParametersRequestOf((1..11).map { "/app/secret-$it" })
        }
        assertFailsWith<IllegalArgumentException> {
            getParametersByPathRequestOf(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            getParametersByPathRequestOf("/app", nextToken = " ")
        }

        val request = getParametersByPathRequestOf(path = "/app", maxResults = 5, nextToken = "token")

        log.debug { "request: $request" }
        request.path() shouldBeEqualTo "/app"
        request.maxResults() shouldBeEqualTo 5
        request.nextToken() shouldBeEqualTo "token"
    }

    @Test
    fun `secure and non secure reads map withDecryption and preserve missing parameter exception`() {

        val requestSlot = slot<GetParameterRequest>()

        every {
            client.getParameter(capture(requestSlot))
        } returns GetParameterResponse.builder()
            .parameter(
                Parameter.builder()
                    .name("/app/secret")
                    .value(SENTINEL)
                    .type(ParameterType.SECURE_STRING)
                    .build()
            )
            .build()

        val secret = client.getSecureParameter("/app/secret")

        secret.reveal() shouldBeEqualTo SENTINEL
        requestSlot.captured.withDecryption().shouldBeTrue()

        every {
            client.getParameter(capture(requestSlot))
        } returns GetParameterResponse.builder()
            .parameter(
                Parameter.builder()
                    .name("/app/plain")
                    .value("plain")
                    .type(ParameterType.STRING)
                    .build()
            )
            .build()

        val response = client.getParameter("/app/plain")

        response.parameter().value() shouldBeEqualTo "plain"
        requestSlot.captured.withDecryption().shouldBeFalse()

        val missing = ParameterNotFoundException.builder()
            .message("missing parameter")
            .build()

        every { client.getParameter(any<GetParameterRequest>()) } throws missing

        val error = assertFailsWith<ParameterNotFoundException> {
            client.getSecureParameter("/app/missing")
        }
        error.message shouldContain "missing parameter"
    }

    @Test
    fun `put secure parameter accepts redacted value and prevents raw string secure overload`() {
        val client = mockk<SsmClient>()
        val putSlot = slot<PutParameterRequest>()
        every { client.putParameter(capture(putSlot)) } returns PutParameterResponse.builder().version(1L).build()
        val secret = awsSecretValueOf(SENTINEL)

        client.putSecureParameter(name = "/app/secret", value = secret, overwrite = true)

        putSlot.captured.name() shouldBeEqualTo "/app/secret"
        putSlot.captured.value() shouldBeEqualTo SENTINEL
        putSlot.captured.type() shouldBeEqualTo ParameterType.SECURE_STRING
        putSlot.captured.overwrite().shouldBeTrue()
        putSlot.captured.toString() shouldNotContain SENTINEL
        secret.toString() shouldNotContain SENTINEL
    }

    @Test
    fun `collection helpers preserve raw partial responses and make one sdk call`() {
        val parametersResponse = GetParametersResponse.builder().invalidParameters("/app/missing").build()
        val pathResponse = GetParametersByPathResponse.builder().nextToken("next").build()

        every { client.getParameters(any<GetParametersRequest>()) } returns parametersResponse
        every { client.getParametersByPath(any<GetParametersByPathRequest>()) } returns pathResponse

        client.getParameters(listOf("/app/a", "/app/b")) shouldBeEqualTo parametersResponse
        client.getParametersByPath(path = "/app", maxResults = 5, nextToken = "token") shouldBeEqualTo pathResponse

        verify(exactly = 1) { client.getParameters(any<GetParametersRequest>()) }
        verify(exactly = 1) { client.getParametersByPath(any<GetParametersByPathRequest>()) }
    }

    @Test
    fun `async coroutine adapters await and propagate cancellation`() = runTest {
        every {
            asyncClient.getParameter(any<GetParameterRequest>())
        } returns completableFutureOf(
            GetParameterResponse.builder()
                .parameter(
                    Parameter.builder()
                        .name("/app/secret")
                        .value(SENTINEL)
                        .build()
                )
                .build(),
        )

        asyncClient.getSecureParameter("/app/secret").reveal() shouldBeEqualTo SENTINEL

        val cancelled = CompletableFuture<GetParameterResponse>()
        cancelled.completeExceptionally(CancellationException("cancelled"))

        every { asyncClient.getParameter(any<GetParameterRequest>()) } returns cancelled

        assertFailsWith<CancellationException> {
            asyncClient.getParameterAsync("/app/secret").await()
        }
    }

    @Test
    fun `put secure parameter request uses redacted input`() {
        val request = putSecureParameterRequestOf(
            name = "/app/secret",
            value = awsSecretValueOf(SENTINEL),
            overwrite = true,
        )

        log.debug { "request: $request" }
        request.name() shouldBeEqualTo "/app/secret"
        request.value() shouldBeEqualTo SENTINEL
        request.type() shouldBeEqualTo ParameterType.SECURE_STRING
        request.toString() shouldNotContain SENTINEL
    }
}
