package io.bluetape4k.aws.kotlin.eventbridge

import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class EventBridgeClientSupportTest {

    companion object: KLogging()

    @Test
    fun `eventBridgeClientOf creates caller owned client`() {
        val client: EventBridgeClient = eventBridgeClientOf(
            endpointUrl = Url.parse("http://localhost:4566"),
            region = "us-east-1",
        )

        client.shouldNotBeNull()
        client.config.shouldNotBeNull()
        client.config.endpointUrl shouldBeEqualTo Url.parse("http://localhost:4566")
        client.config.region shouldBeEqualTo "us-east-1"
        client.config.httpClient.shouldBeInstanceOf<HttpClientEngine>()

        client.close()
    }
}
