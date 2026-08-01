package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.InferenceConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import io.bluetape4k.aws.kotlin.bedrock.model.converseStreamRequestOf
import io.bluetape4k.aws.kotlin.bedrock.model.textDeltaOrNull
import io.bluetape4k.coroutines.flow.extensions.castNotNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Bedrock 네이티브 스트리밍 응답을 전달하는 콜드 Flow를 반환합니다.
 *
 * 수집할 때마다 과금되는 서비스 작업을 한 번 호출합니다. 네이티브 이벤트 순서, SDK 실패,
 * 구조화된 취소를 보존합니다. 버퍼링, 재생, 재시도, 병렬 매핑 또는 프롬프트/출력 로깅 없이
 * SDK 응답 범위 안에서 네이티브 스트림을 수집합니다. 이 클라이언트는 호출자가 소유합니다.
 * 단기 클라이언트 범위를 수집에 맞추려면 [withBedrockRuntimeClient]를 사용하세요.
 */
fun BedrockRuntimeClient.converseStreamFlow(
    request: ConverseStreamRequest,
): Flow<ConverseStreamOutput> =
    flow {
        converseStream(request) { response ->
            response.stream?.collect { emit(it) }
        }
    }

/**
 * 모델에 종속되지 않는 Bedrock 네이티브 스트리밍 요청용 콜드 Flow를 반환합니다.
 *
 * 수집할 때마다 요청 하나를 구성하고 호출합니다. 버퍼링, 재생, 재시도 또는 프롬프트/출력 로깅 없이
 * 네이티브 이벤트 순서, SDK 실패, 구조화된 취소를 보존합니다. 이 클라이언트는 호출자가 소유합니다.
 * [withBedrockRuntimeClient]를 사용할 때는 클라이언트가 닫히기 전에 범위 블록 안에서 최종 수집을 완료해야 합니다.
 */
inline fun BedrockRuntimeClient.converseStreamFlow(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    crossinline builder: ConverseStreamRequest.Builder.() -> Unit = {},
): Flow<ConverseStreamOutput> =
    converseStreamFlow(
        converseStreamRequestOf(
            modelId = modelId,
            messages = messages,
            inferenceConfig = inferenceConfig,
            builder = builder,
        ),
    )

/**
 * 업스트림 순서대로 네이티브 텍스트 델타 페이로드를 선택합니다.
 *
 * 빈 텍스트는 보존하고 텍스트가 아닌 이벤트는 걸러냅니다. 이 연산자는 이벤트를 버퍼링,
 * 재생, 재시도, 로깅 또는 병렬 매핑하지 않습니다.
 */
fun Flow<ConverseStreamOutput>.textDeltaFlow(): Flow<String> =
    map(ConverseStreamOutput::textDeltaOrNull).castNotNull<String>()
