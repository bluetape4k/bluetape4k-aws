package io.bluetape4k.aws.bedrock

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.Message

/**
 * Executes one native Bedrock `Converse` request and suspends for its response.
 *
 * Coroutine cancellation is forwarded to the backing SDK future. The raw SDK
 * response and exceptions are preserved; this helper neither closes the
 * external client nor adds retries or timeouts.
 */
suspend inline fun BedrockRuntimeAsyncClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse =
    converseAsync(modelId, messages, inferenceConfig, builder).await()
