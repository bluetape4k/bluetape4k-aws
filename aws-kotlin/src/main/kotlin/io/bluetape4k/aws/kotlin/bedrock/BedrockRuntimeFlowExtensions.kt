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
 * Returns a cold Flow over the native Bedrock streaming response.
 *
 * Each collection invokes the billable service operation once. Native event
 * order, SDK failures, and structured cancellation are preserved. The native
 * stream is collected inside the SDK response scope, without buffering,
 * replay, retries, parallel mapping, or prompt/output logging. The caller owns
 * this client; use [withBedrockRuntimeClient] to scope a short-lived client to
 * collection.
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
 * Returns a cold Flow for a model-neutral native Bedrock streaming request.
 *
 * Each collection builds and invokes one request. Native event order, SDK
 * failures, and structured cancellation are preserved without buffering,
 * replay, retries, or prompt/output logging. The caller owns this client; when
 * using [withBedrockRuntimeClient], terminal collection must finish inside the
 * scoped block before the client is closed.
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
 * Selects native text delta payloads in upstream order.
 *
 * Non-text events are filtered while empty text is preserved. This operator
 * does not buffer, replay, retry, log, or map events in parallel.
 */
fun Flow<ConverseStreamOutput>.textDeltaFlow(): Flow<String> =
    map(ConverseStreamOutput::textDeltaOrNull).castNotNull<String>()
