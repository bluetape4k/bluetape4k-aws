package io.bluetape4k.aws.bedrock.consumer

import io.bluetape4k.aws.bedrock.bedrockRuntimeAsyncClientOf
import io.bluetape4k.aws.bedrock.converseStreamFlow
import io.bluetape4k.aws.bedrock.model.converseStreamRequestOf
import io.bluetape4k.aws.bedrock.model.userMessageOf
import io.bluetape4k.aws.bedrock.textDeltaFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

fun javaBedrockTextFlow(
    modelId: String,
    prompt: String,
): Flow<String> =
    flow {
        bedrockRuntimeAsyncClientOf().use { client ->
            val request = converseStreamRequestOf(
                modelId = modelId,
                messages = listOf(userMessageOf(prompt)),
            )
            emitAll(client.converseStreamFlow(request).textDeltaFlow())
        }
    }
