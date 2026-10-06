package io.bluetape4k.aws.kotlin.dynamodbstreams

import aws.smithy.kotlin.runtime.http.engine.CloseableHttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DynamoDbStreamsClientSupportTest {

    companion object: KLogging()

    private val httpClient = mockk<CloseableHttpClientEngine>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(httpClient)
    }

    @Test
    fun `factory applies endpoint and region while preserving caller owned HTTP engine`() {

        val client = dynamoDbStreamsClientOf(
            endpointUrl = Url.parse("http://localhost:4566"),
            region = "us-east-1",
            httpClient = httpClient,
        )

        try {
            client.config.endpointUrl.toString() shouldBeEqualTo "http://localhost:4566"
            client.config.region shouldBeEqualTo "us-east-1"
            client.config.httpClient shouldBeSameInstanceAs httpClient
        } finally {
            client.close()
        }

        verify(exactly = 0) { httpClient.close() }
    }
}
