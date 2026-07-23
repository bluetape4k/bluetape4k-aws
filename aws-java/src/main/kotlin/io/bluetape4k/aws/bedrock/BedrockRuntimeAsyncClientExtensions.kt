package io.bluetape4k.aws.bedrock

import io.bluetape4k.aws.bedrock.model.converseRequestOf
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.Message
import java.util.concurrent.CompletableFuture

/**
 * Starts one native Bedrock `Converse` request and returns its original future.
 *
 * The raw SDK response, future, and exceptions are preserved. This helper
 * neither closes the external client nor adds retries or timeouts.
 */
inline fun BedrockRuntimeAsyncClient.converseAsync(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): CompletableFuture<ConverseResponse> =
    converse(converseRequestOf(modelId, messages, inferenceConfig, builder))
