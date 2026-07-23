package io.bluetape4k.aws.bedrock

import io.bluetape4k.aws.bedrock.model.converseRequestOf
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.Message

/**
 * Executes one native Bedrock `Converse` request.
 *
 * The raw SDK response and exceptions are preserved. This helper neither
 * closes the external client nor adds retries or timeouts.
 */
inline fun BedrockRuntimeClient.converse(
    modelId: String,
    messages: Collection<Message>,
    inferenceConfig: InferenceConfiguration? = null,
    builder: ConverseRequest.Builder.() -> Unit = {},
): ConverseResponse =
    converse(converseRequestOf(modelId, messages, inferenceConfig, builder))
