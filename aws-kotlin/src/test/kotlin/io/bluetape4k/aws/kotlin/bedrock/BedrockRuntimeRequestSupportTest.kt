package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.InferenceConfiguration
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.kotlin.bedrock.model.contentBlockOf
import io.bluetape4k.aws.kotlin.bedrock.model.converseRequestOf
import io.bluetape4k.aws.kotlin.bedrock.model.converseStreamRequestOf
import io.bluetape4k.aws.kotlin.bedrock.model.userMessageOf
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith

class BedrockRuntimeRequestSupportTest {

    @Test
    fun `content and user message use native sealed types`() {
        contentBlockOf("hello") shouldBeEqualTo ContentBlock.Text("hello")

        val message = userMessageOf("hello") {
            role = ConversationRole.Assistant
            content = listOf(ContentBlock.Text("builder"))
        }
        message.role shouldBeEqualTo ConversationRole.User
        message.content shouldBeEqualTo listOf(ContentBlock.Text("hello"))
    }

    @Test
    fun `request keeps helper-owned values and explicit inference config`() {
        val inference = InferenceConfiguration {
            maxTokens = 64
            temperature = 0.2F
        }
        val message = userMessageOf("hello")
        val request = converseRequestOf(
            modelId = "model-id",
            messages = listOf(message),
            inferenceConfig = inference,
        ) {
            modelId = "builder-model"
            messages = emptyList()
            inferenceConfig = InferenceConfiguration { maxTokens = 1 }
        }

        request.modelId shouldBeEqualTo "model-id"
        request.messages shouldBeEqualTo listOf(message)
        request.inferenceConfig shouldBeEqualTo inference
    }

    @Test
    fun `null inference config preserves builder value`() {
        converseStreamRequestOf(
            modelId = "model-id",
            messages = listOf(userMessageOf("hello")),
        ) {
            inferenceConfig = InferenceConfiguration { maxTokens = 17 }
        }.inferenceConfig?.maxTokens shouldBeEqualTo 17
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
