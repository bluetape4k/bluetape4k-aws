package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.InferenceConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import io.bluetape4k.aws.kotlin.bedrock.model.converseRequestOf

/**
 * 모델에 종속되지 않는 네이티브 SDK 요청으로 Bedrock Converse를 한 번 호출합니다.
 *
 * 이 확장은 재시도, 타임아웃 또는 클라이언트 소유권을 추가하지 않고 네이티브 [ConverseResponse],
 * SDK 실패, 코루틴 취소를 보존합니다. 이 호출이 단기 클라이언트를 소유해야 한다면
 * [withBedrockRuntimeClient]를 사용하세요.
 */
suspend inline fun BedrockRuntimeClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    crossinline builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse =
    converse(
        converseRequestOf(
            modelId = modelId,
            messages = messages,
            inferenceConfig = inferenceConfig,
            builder = builder,
        ),
    )
