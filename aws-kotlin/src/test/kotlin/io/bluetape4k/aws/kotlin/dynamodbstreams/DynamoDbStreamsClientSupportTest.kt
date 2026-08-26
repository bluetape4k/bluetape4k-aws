package io.bluetape4k.aws.kotlin.dynamodbstreams

import aws.sdk.kotlin.services.dynamodbstreams.DynamoDbStreamsClient
import aws.smithy.kotlin.runtime.http.engine.CloseableHttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class DynamoDbStreamsClientSupportTest {

    @Test
    fun `factory applies endpoint and region while preserving caller owned HTTP engine`() {
        val httpClient = mockk<CloseableHttpClientEngine>(relaxed = true)
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
