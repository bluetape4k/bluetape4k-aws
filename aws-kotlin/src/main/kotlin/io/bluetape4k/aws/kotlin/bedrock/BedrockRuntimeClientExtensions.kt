package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.InferenceConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import io.bluetape4k.aws.kotlin.bedrock.model.converseRequestOf

/**
 * Invokes Bedrock Converse once with a model-neutral native SDK request.
 *
 * This extension preserves the native [ConverseResponse], SDK failures, and
 * coroutine cancellation without adding retries, timeouts, or client
 * ownership. Use [withBedrockRuntimeClient] when this call should own a
 * short-lived client.
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
