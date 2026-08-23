package io.bluetape4k.aws.lambda

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.LambdaAsyncClientBuilder
import java.net.URI

class LambdaAsyncClientSupportTest {

    @Test
    fun `application async factory registers client and lets builder override explicit settings`() {
        val builder = mockk<LambdaAsyncClientBuilder>(relaxed = true)
        val client = mockk<LambdaAsyncClient>(relaxed = true)
        val endpoint = URI("http://localhost:4566")
        val httpClient = mockk<SdkAsyncHttpClient>()
        val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))

        mockkStatic(LambdaAsyncClient::class)
        mockkObject(ShutdownQueue)
        try {
            every { LambdaAsyncClient.builder() } returns builder
            every { builder.build() } returns client
            every { builder.endpointOverride(any()) } returns builder
            every { builder.region(any()) } returns builder
            every { builder.credentialsProvider(any()) } returns builder
            every { builder.httpClient(httpClient) } returns builder

            val result = lambdaAsyncClientOf(
                endpoint = endpoint,
                region = Region.AP_NORTHEAST_2,
                credentialsProvider = credentials,
                httpClient = httpClient,
            ) {
                endpointOverride(URI("http://localhost:4567"))
            }

            result shouldBeSameInstanceAs client
            verify(exactly = 1) { builder.endpointOverride(endpoint) }
            verify(exactly = 1) { builder.region(Region.AP_NORTHEAST_2) }
            verify(exactly = 1) { builder.credentialsProvider(credentials) }
            verify(exactly = 1) { builder.httpClient(httpClient) }
            verify(exactly = 1) { builder.endpointOverride(URI("http://localhost:4567")) }
            verify(exactly = 1) { ShutdownQueue.register(client) }
        } finally {
            unmockkObject(ShutdownQueue)
            unmockkStatic(LambdaAsyncClient::class)
        }
    }

    @Test
    fun `with async factory closes service client without registering or closing external HTTP client`() = runTest {
        val builder = mockk<LambdaAsyncClientBuilder>(relaxed = true)
        val client = mockk<LambdaAsyncClient>(relaxed = true)
        val httpClient = mockk<SdkAsyncHttpClient>(relaxed = true)

        mockkStatic(LambdaAsyncClient::class)
        mockkObject(ShutdownQueue)
        try {
            every { LambdaAsyncClient.builder() } returns builder
            every { builder.build() } returns client
            every { builder.httpClient(httpClient) } returns builder

            withLambdaAsyncClient(httpClient = httpClient) { scopedClient ->
                scopedClient shouldBeSameInstanceAs client
            }

            verify(exactly = 1) { client.close() }
            verify(exactly = 0) { ShutdownQueue.register(any<LambdaAsyncClient>()) }
            verify(exactly = 0) { httpClient.close() }
        } finally {
            unmockkObject(ShutdownQueue)
            unmockkStatic(LambdaAsyncClient::class)
        }
    }

    @Test
    fun `with async factory closes service client when coroutine is cancelled`() = runTest {
        val builder = mockk<LambdaAsyncClientBuilder>(relaxed = true)
        val client = mockk<LambdaAsyncClient>(relaxed = true)
        val httpClient = mockk<SdkAsyncHttpClient>()

        mockkStatic(LambdaAsyncClient::class)
        mockkObject(ShutdownQueue)
        try {
            every { LambdaAsyncClient.builder() } returns builder
            every { builder.build() } returns client
            every { builder.httpClient(httpClient) } returns builder

            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                withTimeout(1) {
                    withLambdaAsyncClient<Unit>(httpClient = httpClient) {
                        delay(10)
                    }
                }
            }

            verify(exactly = 1) { client.close() }
        } finally {
            unmockkObject(ShutdownQueue)
            unmockkStatic(LambdaAsyncClient::class)
        }
    }
}
