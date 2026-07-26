package io.bluetape4k.aws.kinesis.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kinesis.model.DeleteStreamRequest

/**
 * Builds a [DeleteStreamRequest] with a DSL block.
 *
 * ```kotlin
 * val req = deleteStreamRequest {
 *     streamName("my-stream")
 * }
 * ```
 */
inline fun deleteStreamRequest(
    builder: DeleteStreamRequest.Builder.() -> Unit,
): DeleteStreamRequest =
    DeleteStreamRequest.builder().apply(builder).build()

/**
 * Creates a [DeleteStreamRequest] from a stream name.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 *
 * ```kotlin
 * val req = deleteStreamRequestOf("my-stream")
 * ```
 */
inline fun deleteStreamRequestOf(
    streamName: String,
    builder: DeleteStreamRequest.Builder.() -> Unit = {},
): DeleteStreamRequest {
    streamName.requireNotBlank("streamName")
    return deleteStreamRequest {
        streamName(streamName)
        builder()
    }
}
