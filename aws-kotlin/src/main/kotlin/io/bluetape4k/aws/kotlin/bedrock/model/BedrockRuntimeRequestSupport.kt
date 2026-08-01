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
 * 모델에 종속되지 않는 Bedrock 네이티브 텍스트 내용을 구성합니다.
 */
fun contentBlockOf(text: String): ContentBlock {
    text.requireNotBlank("text")
    return ContentBlock.Text(text)
}

/**
 * 모델에 종속되지 않는 Bedrock 네이티브 사용자 [Message]를 구성합니다.
 *
 * 사용자 역할과 텍스트 내용은 도우미가 소유하며 [builder]보다 우선합니다.
 * 다른 네이티브 SDK 필드는 [builder]를 통해 사용할 수 있습니다.
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
 * 모델에 종속되지 않는 네이티브 [ConverseRequest]를 구성합니다.
 *
 * [modelId], [messages], null이 아닌 [inferenceConfig]는 도우미가 소유합니다.
 * 다른 모델별 필드는 [builder]를 통해 사용할 수 있습니다.
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
 * 모델에 종속되지 않는 네이티브 [ConverseStreamRequest]를 구성합니다.
 *
 * [modelId], [messages], null이 아닌 [inferenceConfig]는 도우미가 소유합니다.
 * 다른 모델별 필드는 [builder]를 통해 사용할 수 있습니다.
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
