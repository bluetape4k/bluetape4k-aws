package io.bluetape4k.aws.bedrock

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.utils.ShutdownQueue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClientBuilder
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeServiceClientConfiguration
import kotlin.test.assertFailsWith
import java.net.URI
import java.util.Optional

class BedrockRuntimeClientSupportTest {

    private val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test"),
    )

    @Test
    fun `sync and async factories create closeable clients`() {
        bedrockRuntimeClientOf(
            endpoint = URI("http://localhost:4566"),
            region = Region.US_EAST_1,
            credentialsProvider = credentials,
        ).shouldNotBeNull().close()

        bedrockRuntimeAsyncClientOf(
            endpoint = URI("http://localhost:4566"),
            region = Region.US_EAST_1,
            credentialsProvider = credentials,
        ).shouldNotBeNull().close()
    }

    @Test
    fun `trusted endpoints accept HTTPS and literal loopback HTTP`() {
        listOf(
            "https://example.com",
            "http://localhost:4566",
            "http://LOCALHOST:4566",
            "http://127.0.0.1:4566",
            "http://[::1]:4566",
        ).forEach { endpoint ->
            URI(endpoint).requireTrustedBedrockEndpoint()
        }
    }

    @Test
    fun `plain HTTP endpoint must use a literal loopback host`() {
        listOf(
            "http://example.com",
            "http://127.0.0.2",
            "http://[::2]",
            "http:///missing-host",
            "https:///missing-host",
            "ftp://localhost/resource",
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException>(endpoint) {
                URI(endpoint).requireTrustedBedrockEndpoint()
            }
        }
    }

    @Test
    fun `raw builders reject an untrusted final endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            bedrockRuntimeClient {
                endpointOverride(URI("http://example.com"))
                region(Region.US_EAST_1)
                credentialsProvider(credentials)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            bedrockRuntimeAsyncClient {
                endpointOverride(URI("http://example.com"))
                region(Region.US_EAST_1)
                credentialsProvider(credentials)
            }
        }
    }

    @Test
    fun `Of factories reject an untrusted builder-only endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            bedrockRuntimeClientOf(
                region = Region.US_EAST_1,
                credentialsProvider = credentials,
            ) {
                endpointOverride(URI("http://example.com"))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            bedrockRuntimeAsyncClientOf(
                region = Region.US_EAST_1,
                credentialsProvider = credentials,
            ) {
                endpointOverride(URI("http://example.com"))
            }
        }
    }

    @Test
    fun `invalid provisional clients close once and are not registered`() {
        val endpoint = URI("http://example.com")
        val configuration = mockk<BedrockRuntimeServiceClientConfiguration>()
        every { configuration.endpointOverride() } returns Optional.of(endpoint)

        val syncBuilder = mockk<BedrockRuntimeClientBuilder>(relaxed = true)
        val syncClient = mockk<BedrockRuntimeClient>(relaxed = true)
        every { syncBuilder.httpClient(any<SdkHttpClient>()) } returns syncBuilder
        every { syncBuilder.build() } returns syncClient
        every { syncClient.serviceClientConfiguration() } returns configuration

        val asyncBuilder = mockk<BedrockRuntimeAsyncClientBuilder>(relaxed = true)
        val asyncClient = mockk<BedrockRuntimeAsyncClient>(relaxed = true)
        every { asyncBuilder.httpClient(any<SdkAsyncHttpClient>()) } returns asyncBuilder
        every { asyncBuilder.build() } returns asyncClient
        every { asyncClient.serviceClientConfiguration() } returns configuration

        mockkStatic(BedrockRuntimeClient::class)
        mockkStatic(BedrockRuntimeAsyncClient::class)
        mockkObject(ShutdownQueue)
        try {
            every { BedrockRuntimeClient.builder() } returns syncBuilder
            every { BedrockRuntimeAsyncClient.builder() } returns asyncBuilder

            assertFailsWith<IllegalArgumentException> {
                bedrockRuntimeClientOf(httpClient = mockk<SdkHttpClient>())
            }
            assertFailsWith<IllegalArgumentException> {
                bedrockRuntimeAsyncClientOf(httpClient = mockk<SdkAsyncHttpClient>())
            }

            verify(exactly = 1) { syncClient.close() }
            verify(exactly = 1) { asyncClient.close() }
            verify(exactly = 0) { ShutdownQueue.register(syncClient) }
            verify(exactly = 0) { ShutdownQueue.register(asyncClient) }
        } finally {
            unmockkObject(ShutdownQueue)
            unmockkStatic(BedrockRuntimeAsyncClient::class)
            unmockkStatic(BedrockRuntimeClient::class)
        }
    }

    @Test
    fun `explicit endpoint takes precedence over builder endpoint`() {
        bedrockRuntimeClientOf(
            endpoint = URI("http://localhost:4566"),
            region = Region.US_EAST_1,
            credentialsProvider = credentials,
        ) {
            endpointOverride(URI("http://example.com"))
        }.close()

        bedrockRuntimeAsyncClientOf(
            endpoint = URI("http://localhost:4566"),
            region = Region.US_EAST_1,
            credentialsProvider = credentials,
        ) {
            endpointOverride(URI("http://example.com"))
        }.close()
    }
}
