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
import org.junit.jupiter.api.Test
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.services.s3tables.S3TablesClient
import software.amazon.awssdk.services.s3tables.S3TablesClientBuilder
import java.net.URI

class S3TablesClientSupportTest {

    @Test
    fun `application factory registers client and with factory closes only service client`() {
        val builder = mockk<S3TablesClientBuilder>(relaxed = true)
        val client = mockk<S3TablesClient>(relaxed = true)
        val httpClient = mockk<SdkHttpClient>(relaxed = true)
        mockkStatic(S3TablesClient::class)
        mockkObject(ShutdownQueue)
        try {
            every { S3TablesClient.builder() } returns builder
            every { builder.build() } returns client
            every { builder.endpointOverride(any<URI>()) } returns builder
            every { builder.httpClient(any()) } returns builder

            s3TablesClientOf(endpoint = URI("http://localhost:4566"), httpClient = httpClient)
                .shouldBeSameInstanceAs(client)
            verify(exactly = 1) { ShutdownQueue.register(client) }

            withS3TablesClient(httpClient = httpClient) { it.shouldBeSameInstanceAs(client) }
            verify(exactly = 1) { client.close() }
            verify(exactly = 0) { httpClient.close() }
        } finally {
            unmockkObject(ShutdownQueue)
            unmockkStatic(S3TablesClient::class)
        }
    }

    @Test
    fun `with factory closes service client after failure`() {
        val client = mockk<S3TablesClient>(relaxed = true)
        val expected = IllegalStateException("boom")
        val actual = assertFailsWith<IllegalStateException> {
            withS3TablesClient(clientFactory = { client }) { throw expected }
        }
        actual.shouldBeSameInstanceAs(expected)
        verify(exactly = 1) { client.close() }
    }
}
