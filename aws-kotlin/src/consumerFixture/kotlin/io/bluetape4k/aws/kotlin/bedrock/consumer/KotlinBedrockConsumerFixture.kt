package io.bluetape4k.aws.kotlin.bedrock.consumer

import io.bluetape4k.aws.kotlin.bedrock.converseStreamFlow
import io.bluetape4k.aws.kotlin.bedrock.model.converseStreamRequestOf
import io.bluetape4k.aws.kotlin.bedrock.model.userMessageOf
import io.bluetape4k.aws.kotlin.bedrock.textDeltaFlow
import io.bluetape4k.aws.kotlin.bedrock.withBedrockRuntimeClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

fun kotlinBedrockTextFlow(
    modelId: String,
    prompt: String,
): Flow<String> =
    flow {
        withBedrockRuntimeClient { client ->
            val request = converseStreamRequestOf(
                modelId = modelId,
                messages = listOf(userMessageOf(prompt)),
            )
            emitAll(client.converseStreamFlow(request).textDeltaFlow())
        }
    }
