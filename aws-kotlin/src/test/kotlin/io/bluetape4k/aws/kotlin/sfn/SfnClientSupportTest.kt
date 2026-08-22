package io.bluetape4k.aws.kotlin.sfn

import aws.sdk.kotlin.services.sfn.SfnClient
import aws.smithy.kotlin.runtime.http.engine.CloseableHttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SfnClientSupportTest {

    @Test
    fun `sfnClientOf lets builder override explicit endpoint and region`() {
        val externalHttpClient = mockk<CloseableHttpClientEngine>(relaxed = true)
        val explicitEndpoint = Url.parse("http://explicit.example")
        val builderEndpoint = Url.parse("http://builder.example")
        val client = sfnClientOf(
            endpointUrl = explicitEndpoint,
            region = "explicit-region",
            httpClient = externalHttpClient,
        ) {
            endpointUrl = builderEndpoint
            region = "builder-region"
        }

        try {
            client.config.endpointUrl shouldBeEqualTo builderEndpoint
            client.config.region shouldBeEqualTo "builder-region"
            client.config.httpClient shouldBeSameInstanceAs externalHttpClient
        } finally {
            client.close()
        }

        verify(exactly = 0) { externalHttpClient.close() }
    }

    @Test
    fun `withSfnClient closes exactly once after normal return`() = runTest {
        val client = mockk<SfnClient>(relaxed = true)
        every { client.close() } just runs

        withSfnClient(clientFactory = { client }) { it shouldBeSameInstanceAs client }

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `withSfnClient closes exactly once after block failure`() = runTest {
        val client = mockk<SfnClient>(relaxed = true)
        every { client.close() } just runs
        val expected = IllegalStateException("boom")

        val actual = assertFailsWith<IllegalStateException> {
            withSfnClient(clientFactory = { client }) { throw expected }
        }

        actual shouldBeSameInstanceAs expected
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `withSfnClient closes exactly once after cancellation`() = runTest {
        val client = mockk<SfnClient>(relaxed = true)
        every { client.close() } just runs
        val job = launch {
            withSfnClient(clientFactory = { client }) { awaitCancellation() }
        }
        runCurrent()

        job.cancelAndJoin()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `withSfnClient leaves caller owned HTTP engine open`() = runTest {
        val externalHttpClient = mockk<CloseableHttpClientEngine>(relaxed = true)

        withSfnClient(
            endpointUrl = Url.parse("http://localhost:4566"),
            region = "us-east-1",
            httpClient = externalHttpClient,
        ) { it.config.httpClient shouldBeSameInstanceAs externalHttpClient }

        verify(exactly = 0) { externalHttpClient.close() }
    }

    @Test
    fun `application client can be explicitly closed by caller`() {
        val client = mockk<SfnClient>(relaxed = true)
        every { client.close() } just runs

        client.close()

        verify(exactly = 1) { client.close() }
    }
}
