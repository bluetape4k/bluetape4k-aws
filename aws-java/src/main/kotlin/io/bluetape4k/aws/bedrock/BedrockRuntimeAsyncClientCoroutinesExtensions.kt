package io.bluetape4k.aws.bedrock

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.Message

/**
 * Bedrock 네이티브 `Converse` 요청 하나를 실행하고 응답까지 일시 중단합니다.
 *
 * 코루틴 취소는 기반 SDK Future로 전달됩니다. SDK 원본 응답과 예외는 보존하며,
 * 이 도우미는 외부 클라이언트를 닫거나 재시도 또는 타임아웃을 추가하지 않습니다.
 */
suspend inline fun BedrockRuntimeAsyncClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse =
    converseAsync(modelId, messages, inferenceConfig, builder).await()
