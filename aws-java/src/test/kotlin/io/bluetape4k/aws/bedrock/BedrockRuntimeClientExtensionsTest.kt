package io.bluetape4k.aws.bedrock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.AbstractAwsTest
import io.bluetape4k.aws.bedrock.model.userMessageOf
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse

class BedrockRuntimeClientExtensionsTest: AbstractAwsTest() {

    companion object: KLogging()

    private val client = mockk<BedrockRuntimeClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `sync convenience call delegates once and preserves response identity`() {
        val expected = ConverseResponse.builder().build()
        every { client.converse(any<ConverseRequest>()) } returns expected

        client.converse(
            "model-id",
            listOf(userMessageOf("hello"))
        ) shouldBeSameInstanceAs expected

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
