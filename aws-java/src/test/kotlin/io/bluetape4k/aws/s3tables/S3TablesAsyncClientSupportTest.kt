package io.bluetape4k.aws.s3tables

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.utils.ShutdownQueue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.services.s3tables.S3TablesAsyncClient
import software.amazon.awssdk.services.s3tables.S3TablesAsyncClientBuilder

class S3TablesAsyncClientSupportTest {

    @Test
    fun `application async factory registers client and forwards caller settings`() {
        val builder = mockk<S3TablesAsyncClientBuilder>(relaxed = true)
        val client = mockk<S3TablesAsyncClient>(relaxed = true)
        val httpClient = mockk<SdkAsyncHttpClient>(relaxed = true)
        mockkStatic(S3TablesAsyncClient::class)
        mockkObject(ShutdownQueue)
        try {
            every { S3TablesAsyncClient.builder() } returns builder
            every { builder.build() } returns client
            every { builder.httpClient(any()) } returns builder

            s3TablesAsyncClientOf(httpClient = httpClient).shouldBeSameInstanceAs(client)

            verify(exactly = 1) { builder.httpClient(httpClient) }
            verify(exactly = 1) { ShutdownQueue.register(client) }
        } finally {
            unmockkObject(ShutdownQueue)
            unmockkStatic(S3TablesAsyncClient::class)
        }
    }

    @Test
    fun `with async factory closes only service client after failure`() = runTest {
        val client = mockk<S3TablesAsyncClient>(relaxed = true)
        val expected = IllegalStateException("boom")

        val actual = assertFailsWith<IllegalStateException> {
            withS3TablesAsyncClient(clientFactory = { client }) { throw expected }
        }

        actual.shouldBeSameInstanceAs(expected)
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `with async factory closes service client after cancellation`() = runTest {
        val client = mockk<S3TablesAsyncClient>(relaxed = true)
        val job = launch {
            withS3TablesAsyncClient(clientFactory = { client }) {
                awaitCancellation()
            }
        }
        runCurrent()

        job.cancelAndJoin()

        verify(exactly = 1) { client.close() }
    }
}
