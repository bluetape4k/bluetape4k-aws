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
 * Bedrock 네이티브 텍스트 [ContentBlock]을 구성합니다.
 *
 * [text]는 도우미가 소유하며 [builder]보다 우선합니다. 도우미는 모델에 종속되지 않으며,
 * 네이티브 모델별 필드는 계속 사용할 수 있습니다.
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
 * Bedrock 네이티브 사용자 [Message]를 구성합니다.
 *
 * 사용자 역할과 텍스트 내용은 도우미가 소유하며 [builder]보다 우선합니다. 도우미는 모델에
 * 종속되지 않고 다른 네이티브 SDK 필드는 [builder]를 통해 사용할 수 있습니다.
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
 * 모델에 종속되지 않는 네이티브 [ConverseRequest]를 구성합니다.
 *
 * [modelId], [messages], null이 아닌 [inferenceConfig]는 도우미가 소유합니다.
 * 다른 모델별 필드는 [builder]를 통해 사용할 수 있습니다.
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
 * 모델에 종속되지 않는 네이티브 [ConverseStreamRequest]를 구성합니다.
 *
 * [modelId], [messages], null이 아닌 [inferenceConfig]는 도우미가 소유합니다.
 * 다른 모델별 필드는 [builder]를 통해 사용할 수 있습니다.
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
