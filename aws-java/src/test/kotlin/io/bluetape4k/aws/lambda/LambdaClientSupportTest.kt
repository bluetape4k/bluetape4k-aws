package io.bluetape4k.aws.lambda

import io.bluetape4k.assertions.assertFailsWith
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
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.LambdaClientBuilder
import java.net.URI

class LambdaClientSupportTest {

    @Test
    fun `application factory registers client and applies explicit settings`() {
        val builder = mockk<LambdaClientBuilder>(relaxed = true)
        val client = mockk<LambdaClient>(relaxed = true)
        val endpoint = URI("http://localhost:4566")
        val httpClient = mockk<SdkHttpClient>()
        val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))

        mockkStatic(LambdaClient::class)
        mockkObject(ShutdownQueue)
        try {
            every { LambdaClient.builder() } returns builder
            every { builder.build() } returns client
            every { builder.endpointOverride(any()) } returns builder
            every { builder.region(any()) } returns builder
            every { builder.credentialsProvider(any()) } returns builder
            every { builder.httpClient(httpClient) } returns builder

            val result = lambdaClientOf(
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
            unmockkStatic(LambdaClient::class)
        }
    }

    @Test
    fun `with factory closes service client without registering or closing external HTTP client`() {
        val builder = mockk<LambdaClientBuilder>(relaxed = true)
        val client = mockk<LambdaClient>(relaxed = true)
        val httpClient = mockk<SdkHttpClient>(relaxed = true)

        mockkStatic(LambdaClient::class)
        mockkObject(ShutdownQueue)
        try {
            every { LambdaClient.builder() } returns builder
            every { builder.build() } returns client
            every { builder.httpClient(httpClient) } returns builder

            val result = withLambdaClient(httpClient = httpClient) { scopedClient ->
                scopedClient shouldBeSameInstanceAs client
                "result"
            }

            result shouldBeEqualTo "result"
            verify(exactly = 1) { client.close() }
            verify(exactly = 0) { ShutdownQueue.register(any<LambdaClient>()) }
            verify(exactly = 0) { httpClient.close() }
        } finally {
            unmockkObject(ShutdownQueue)
            unmockkStatic(LambdaClient::class)
        }
    }

    @Test
    fun `with factory closes service client when block fails`() {
        val builder = mockk<LambdaClientBuilder>(relaxed = true)
        val client = mockk<LambdaClient>(relaxed = true)
        val httpClient = mockk<SdkHttpClient>()

        mockkStatic(LambdaClient::class)
        mockkObject(ShutdownQueue)
        try {
            every { LambdaClient.builder() } returns builder
            every { builder.build() } returns client
            every { builder.httpClient(httpClient) } returns builder

            assertFailsWith<IllegalStateException> {
                withLambdaClient<Unit>(httpClient = httpClient) { throw IllegalStateException("block failed") }
            }

            verify(exactly = 1) { client.close() }
        } finally {
            unmockkObject(ShutdownQueue)
            unmockkStatic(LambdaClient::class)
        }
    }
}
