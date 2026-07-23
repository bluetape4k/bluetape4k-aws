package io.bluetape4k.aws.bedrock

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.bedrock.model.userMessageOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import kotlin.test.assertFailsWith

class BedrockRuntimeClientExtensionsTest {

    private val client = mockk<BedrockRuntimeClient>()

    @Test
    fun `sync convenience call delegates once and preserves response identity`() {
        val expected = ConverseResponse.builder().build()
        every { client.converse(any<ConverseRequest>()) } returns expected

        client.converse("model-id", listOf(userMessageOf("hello"))) shouldBeSameInstanceAs expected

        verify(exactly = 1) { client.converse(any<ConverseRequest>()) }
    }

    @Test
    fun `sync convenience call validates before SDK invocation`() {
        assertFailsWith<IllegalArgumentException> {
            client.converse(" ", listOf(userMessageOf("hello")))
        }
        verify(exactly = 0) { client.converse(any<ConverseRequest>()) }
    }
}
