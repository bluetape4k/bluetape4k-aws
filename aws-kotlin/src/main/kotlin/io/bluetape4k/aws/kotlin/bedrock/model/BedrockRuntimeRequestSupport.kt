package io.bluetape4k.aws.kotlin.bedrock.model

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.InferenceConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

/**
 * Builds model-neutral native Bedrock text content.
 */
fun contentBlockOf(text: String): ContentBlock {
    text.requireNotBlank("text")
    return ContentBlock.Text(text)
}

/**
 * Builds a model-neutral native Bedrock user [Message].
 *
 * The user role and text content are helper-owned and take precedence over
 * [builder]. Other native SDK fields remain available through [builder].
 */
inline fun userMessageOf(
    text: String,
    crossinline builder: Message.Builder.() -> Unit = {},
): Message {
    text.requireNotBlank("text")
    return Message {
        builder()
        role = ConversationRole.User
        content = listOf(ContentBlock.Text(text))
    }
}

/**
 * Builds a model-neutral native [ConverseRequest].
 *
 * [modelId], [messages], and a non-null [inferenceConfig] are helper-owned.
 * Other model-specific fields remain available through [builder].
 */
inline fun converseRequestOf(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    crossinline builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseRequest {
    modelId.requireNotBlank("modelId")
    messages.requireNotEmpty("messages")
    return ConverseRequest {
        builder()
        this.modelId = modelId
        this.messages = messages.toList()
        inferenceConfig?.let { this.inferenceConfig = it }
    }
}

/**
 * Builds a model-neutral native [ConverseStreamRequest].
 *
 * [modelId], [messages], and a non-null [inferenceConfig] are helper-owned.
 * Other model-specific fields remain available through [builder].
 */
inline fun converseStreamRequestOf(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    crossinline builder: ConverseStreamRequest.Builder.() -> Unit = {},
): ConverseStreamRequest {
    modelId.requireNotBlank("modelId")
    messages.requireNotEmpty("messages")
    return ConverseStreamRequest {
        builder()
        this.modelId = modelId
        this.messages = messages.toList()
        inferenceConfig?.let { this.inferenceConfig = it }
    }
}
