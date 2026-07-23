package io.bluetape4k.aws.bedrock

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.bedrock.model.userMessageOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import java.util.concurrent.CompletableFuture

class BedrockRuntimeAsyncClientExtensionsTest {

    private val client = mockk<BedrockRuntimeAsyncClient>()

    @Test
    fun `async convenience call returns the original future`() {
        val future = CompletableFuture.completedFuture(ConverseResponse.builder().build())
        every { client.converse(any<ConverseRequest>()) } returns future

        client.converseAsync("model-id", listOf(userMessageOf("hello"))) shouldBeSameInstanceAs future

        verify(exactly = 1) { client.converse(any<ConverseRequest>()) }
    }
}
