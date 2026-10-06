package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.InferenceConfiguration
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.kotlin.bedrock.model.contentBlockOf
import io.bluetape4k.aws.kotlin.bedrock.model.converseRequestOf
import io.bluetape4k.aws.kotlin.bedrock.model.converseStreamRequestOf
import io.bluetape4k.aws.kotlin.bedrock.model.userMessageOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class BedrockRuntimeRequestSupportTest {

    companion object: KLogging()

    @Test
    fun `content and user message use native sealed types`() {
        contentBlockOf("hello") shouldBeEqualTo ContentBlock.Text("hello")

        val message = userMessageOf("hello") {
            role = ConversationRole.Assistant
            content = listOf(ContentBlock.Text("builder"))
        }
        log.debug { "message=$message" }
        message.role shouldBeEqualTo ConversationRole.Assistant
        message.content shouldBeEqualTo listOf(ContentBlock.Text("builder"))
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

        log.debug { "request=$request" }
        request.modelId shouldBeEqualTo "builder-model"
        request.messages.shouldBeEmpty()
        request.inferenceConfig shouldBeEqualTo InferenceConfiguration { maxTokens = 1 }
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
