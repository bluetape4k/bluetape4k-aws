package io.bluetape4k.aws.kotlin.s3tables

import aws.sdk.kotlin.services.s3tables.S3TablesClient
import aws.smithy.kotlin.runtime.http.engine.CloseableHttpClientEngine
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

class S3TablesClientSupportTest {

    @Test
    fun `caller-owned factory forwards endpoint region and HTTP engine`() {
        val externalHttpClient = mockk<CloseableHttpClientEngine>(relaxed = true)
        val endpoint = Url.parse("http://localhost:4566")
        val client = s3TablesClientOf(
            endpointUrl = endpoint,
            region = "us-east-1",
            httpClient = externalHttpClient,
        ) {
            region = "ap-northeast-2"
        }

        try {
            client.config.endpointUrl shouldBeEqualTo endpoint
            client.config.region shouldBeEqualTo "ap-northeast-2"
            client.config.httpClient shouldBeSameInstanceAs externalHttpClient
        } finally {
            client.close()
        }

        verify(exactly = 0) { externalHttpClient.close() }
    }

    @Test
    fun `with factory closes service client after success and failure`() = runTest {
        val client = mockk<S3TablesClient>(relaxed = true)
        every { client.close() } just runs
        withS3TablesClient(clientFactory = { client }) { it.shouldBeSameInstanceAs(client) }
        val expected = IllegalStateException("boom")
        val actual = assertFailsWith<IllegalStateException> {
            withS3TablesClient(clientFactory = { client }) { throw expected }
        }
        actual.shouldBeSameInstanceAs(expected)
        verify(exactly = 2) { client.close() }
    }

    @Test
    fun `with factory closes service client after cancellation`() = runTest {
        val client = mockk<S3TablesClient>(relaxed = true)
        every { client.close() } just runs
        val job = launch {
            withS3TablesClient(clientFactory = { client }) {
                awaitCancellation()
            }
        }
        runCurrent()

        job.cancelAndJoin()

        verify(exactly = 1) { client.close() }
    }
}
