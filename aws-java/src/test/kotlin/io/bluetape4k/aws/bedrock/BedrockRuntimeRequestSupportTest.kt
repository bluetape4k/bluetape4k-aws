package io.bluetape4k.aws.bedrock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.bedrock.model.contentBlockOf
import io.bluetape4k.aws.bedrock.model.converseRequestOf
import io.bluetape4k.aws.bedrock.model.converseStreamRequestOf
import io.bluetape4k.aws.bedrock.model.userMessageOf
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import io.bluetape4k.assertions.assertFailsWith

class BedrockRuntimeRequestSupportTest {

    @Test
    fun `content and user message keep helper-owned fields`() {
        contentBlockOf("hello") {
            text("builder text")
        }.text() shouldBeEqualTo "hello"

        val message = userMessageOf("hello") {
            role(ConversationRole.ASSISTANT)
            content(contentBlockOf("builder content"))
        }
        message.role() shouldBeEqualTo ConversationRole.USER
        message.content().single().text() shouldBeEqualTo "hello"
    }

    @Test
    fun `request keeps required inputs and explicit inference config`() {
        val expectedInference = InferenceConfiguration.builder()
            .maxTokens(64)
            .temperature(0.2F)
            .build()
        val request = converseRequestOf(
            modelId = "model-id",
            messages = listOf(userMessageOf("hello")),
            inferenceConfig = expectedInference,
        ) {
            modelId("builder-model")
            messages(emptyList())
            inferenceConfig { it.maxTokens(1) }
            additionalModelRequestFields(
                Document.fromMap(mapOf("top_k" to Document.fromNumber(10))),
            )
        }

        request.modelId() shouldBeEqualTo "model-id"
        request.messages().size shouldBeEqualTo 1
        request.inferenceConfig() shouldBeEqualTo expectedInference
        request.additionalModelRequestFields().shouldNotBeNull()
    }

    @Test
    fun `null inference config preserves builder value`() {
        converseStreamRequestOf(
            modelId = "model-id",
            messages = listOf(userMessageOf("hello")),
        ) {
            inferenceConfig { it.maxTokens(17) }
        }.inferenceConfig().maxTokens() shouldBeEqualTo 17
    }

    @Test
    fun `blank helper inputs and empty messages fail`() {
        assertFailsWith<IllegalArgumentException> { contentBlockOf(" ") }
        assertFailsWith<IllegalArgumentException> { userMessageOf("") }
        assertFailsWith<IllegalArgumentException> {
            converseRequestOf(" ", listOf(userMessageOf("hello")))
        }
        assertFailsWith<IllegalArgumentException> {
            converseStreamRequestOf("model-id", emptyList())
        }
    }
}
