package io.bluetape4k.aws.kotlin.eventbridge

import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test

class EventBridgeClientSupportTest {

    @Test
    fun `eventBridgeClientOf creates caller owned client`() {
        val client = eventBridgeClientOf(
            endpointUrl = Url.parse("http://localhost:4566"),
            region = "us-east-1",
        )

        client.shouldNotBeNull()
        client.close()
    }
}
