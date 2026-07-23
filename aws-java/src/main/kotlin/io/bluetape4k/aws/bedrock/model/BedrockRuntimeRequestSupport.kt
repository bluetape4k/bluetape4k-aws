package io.bluetape4k.aws.bedrock.model

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.Message

/**
 * Builds a native Bedrock text [ContentBlock].
 *
 * [text] is helper-owned and takes precedence over [builder].
 * The helper is model-neutral; native model-specific fields remain available.
 */
inline fun contentBlockOf(
    text: String,
    builder: ContentBlock.Builder.() -> Unit = {},
): ContentBlock {
    text.requireNotBlank("text")
    return ContentBlock.builder()
        .apply(builder)
        .text(text)
        .build()
}

/**
 * Builds a native Bedrock user [Message].
 *
 * The user role and text content are helper-owned and take precedence over
 * [builder]. The helper is model-neutral, and other native SDK fields remain
 * available through [builder].
 */
inline fun userMessageOf(
    text: String,
    builder: Message.Builder.() -> Unit = {},
): Message {
    text.requireNotBlank("text")
    return Message.builder()
        .apply(builder)
        .role(ConversationRole.USER)
        .content(contentBlockOf(text))
        .build()
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
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseRequest {
    modelId.requireNotBlank("modelId")
    messages.requireNotEmpty("messages")
    return ConverseRequest.builder()
        .apply(builder)
        .modelId(modelId)
        .messages(messages)
        .apply { inferenceConfig?.let(::inferenceConfig) }
        .build()
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
    builder: ConverseStreamRequest.Builder.() -> Unit = {},
): ConverseStreamRequest {
    modelId.requireNotBlank("modelId")
    messages.requireNotEmpty("messages")
    return ConverseStreamRequest.builder()
        .apply(builder)
        .modelId(modelId)
        .messages(messages)
        .apply { inferenceConfig?.let(::inferenceConfig) }
        .build()
}
