package io.bluetape4k.aws.sfn

import io.bluetape4k.assertions.shouldBeEqualTo
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.SfnClientBuilder
import java.net.URI

class SfnClientSupportTest {

    @Test
    fun `application factory registers client and applies explicit settings`() {
        val builder = mockk<SfnClientBuilder>(relaxed = true)
        val client = mockk<SfnClient>(relaxed = true)
        val endpoint = URI("http://localhost:4566")
        val httpClient = mockk<SdkHttpClient>()
        val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))

        mockkStatic(SfnClient::class)
        mockkObject(ShutdownQueue)
        try {
            every { SfnClient.builder() } returns builder
            every { builder.build() } returns client
            every { builder.endpointOverride(any()) } returns builder
            every { builder.region(any()) } returns builder
            every { builder.credentialsProvider(any()) } returns builder
            every { builder.httpClient(any()) } returns builder

            val result = sfnClientOf(
                endpoint = endpoint,
                region = Region.AP_NORTHEAST_2,
                credentialsProvider = credentials,
                httpClient = httpClient,
            ) {
                region(Region.US_EAST_1)
            }

            result shouldBeSameInstanceAs client
            verify(exactly = 1) { builder.endpointOverride(endpoint) }
            verify(exactly = 1) { builder.region(Region.AP_NORTHEAST_2) }
            verify(exactly = 1) { builder.credentialsProvider(credentials) }
            verify(exactly = 1) { builder.httpClient(httpClient) }
            verify(exactly = 1) { builder.region(Region.US_EAST_1) }
            verify(exactly = 1) { ShutdownQueue.register(client) }
        } finally {
            unmockkObject(ShutdownQueue)
            unmockkStatic(SfnClient::class)
        }
    }

    @Test
    fun `with factory closes service client without registering it or closing external HTTP client`() {
        val builder = mockk<SfnClientBuilder>(relaxed = true)
        val client = mockk<SfnClient>(relaxed = true)
        val httpClient = mockk<SdkHttpClient>(relaxed = true)

        mockkStatic(SfnClient::class)
        mockkObject(ShutdownQueue)
        try {
            every { SfnClient.builder() } returns builder
            every { builder.build() } returns client
            every { builder.httpClient(any()) } returns builder

            val result = withSfnClient(httpClient = httpClient) { scopedClient ->
                scopedClient shouldBeSameInstanceAs client
                "result"
            }

            result shouldBeEqualTo "result"
            verify(exactly = 1) { client.close() }
            verify(exactly = 0) { ShutdownQueue.register(any<SfnClient>()) }
            verify(exactly = 0) { httpClient.close() }
        } finally {
            unmockkObject(ShutdownQueue)
            unmockkStatic(SfnClient::class)
        }
    }
}
