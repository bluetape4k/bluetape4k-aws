package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith

class BedrockRuntimeClientSupportTest {

    @Test
    fun `caller-owned factory forwards explicit configuration`() {
        val credentialsProvider = mockk<CredentialsProvider>()
        val httpClient = mockk<HttpClientEngine>(relaxed = true)
        val endpoint = Url.parse("http://localhost:4566")

        val client = bedrockRuntimeClientOf(
            endpointUrl = endpoint,
            region = "us-east-1",
            credentialsProvider = credentialsProvider,
            httpClient = httpClient,
        )
        try {
            client.shouldNotBeNull()
            client.config.endpointUrl shouldBeEqualTo endpoint
            client.config.region shouldBeEqualTo "us-east-1"
            client.config.credentialsProvider shouldBeSameInstanceAs credentialsProvider
            client.config.httpClient shouldBeSameInstanceAs httpClient
        } finally {
            client.close()
        }
    }

    @Test
    fun `trusted endpoints accept HTTPS and literal loopback HTTP`() {
        listOf(
            "https://example.com",
            "http://localhost:4566",
            "http://LOCALHOST:4566",
            "http://127.0.0.1:4566",
            "http://[::1]:4566",
        ).forEach { Url.parse(it).requireTrustedBedrockEndpoint() }
    }

    @Test
    fun `plain HTTP endpoint must use a literal loopback host`() {
        listOf(
            "http://example.com",
            "http://127.0.0.2",
            "http://[::2]",
            "ftp://localhost/resource",
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException>(endpoint) {
                Url.parse(endpoint).requireTrustedBedrockEndpoint()
            }
        }
    }

    @Test
    fun `builder-only untrusted endpoint is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            bedrockRuntimeClientOf(region = "us-east-1") {
                endpointUrl = Url.parse("http://example.com")
            }
        }
    }

    @Test
    fun `invalid provisional client closes exactly once`() {
        val client = mockk<BedrockRuntimeClient>(relaxed = true)
        every { client.config.endpointUrl } returns Url.parse("http://example.com")

        assertFailsWith<IllegalArgumentException> {
            client.requireTrustedBedrockConfiguration()
        }

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `with client closes once after success`() = runTest {
        val client = mockk<BedrockRuntimeClient>(relaxed = true)

        withBedrockRuntimeClient(clientFactory = { client }) {
            it shouldBeSameInstanceAs client
        }

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `with client closes once after block failure`() = runTest {
        val client = mockk<BedrockRuntimeClient>(relaxed = true)
        val expected = IllegalStateException("boom")

        val actual = assertFailsWith<IllegalStateException> {
            withBedrockRuntimeClient(clientFactory = { client }) {
                throw expected
            }
        }

        actual shouldBeSameInstanceAs expected
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `with client closes once after cancellation`() = runTest {
        val client = mockk<BedrockRuntimeClient>(relaxed = true)
        val job = launch {
            withBedrockRuntimeClient(clientFactory = { client }) {
                awaitCancellation()
            }
        }
        runCurrent()

        job.cancelAndJoin()

        verify(exactly = 1) { client.close() }
    }
}
