package io.bluetape4k.aws.bedrock

import io.bluetape4k.aws.bedrock.model.converseRequestOf
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.Message

/**
 * Bedrock 네이티브 `Converse` 요청 하나를 실행합니다.
 *
 * SDK 원본 응답과 예외를 보존합니다. 이 도우미는 외부 클라이언트를 닫거나
 * 재시도 또는 타임아웃을 추가하지 않습니다.
 */
inline fun BedrockRuntimeClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse =
    converse(converseRequestOf(modelId, messages, inferenceConfig, builder))
