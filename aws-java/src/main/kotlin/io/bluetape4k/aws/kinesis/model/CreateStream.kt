package io.bluetape4k.aws.kinesis.model

import io.bluetape4k.aws.kinesis.validateKinesisShardCount
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest

/**
 * Builds a [CreateStreamRequest] with a DSL block.
 *
 * ```kotlin
 * val req = createStreamRequest {
 *     streamName("my-stream")
 *     shardCount(1)
 * }
 * ```
 */
inline fun createStreamRequest(
    builder: CreateStreamRequest.Builder.() -> Unit,
): CreateStreamRequest =
    CreateStreamRequest.builder().apply(builder).build()

/**
 * Creates a [CreateStreamRequest] from a stream name and shard count.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 * - [shardCount] must be at least 1.
 *
 * ```kotlin
 * val req = createStreamRequestOf("my-stream", shardCount = 2)
 * // req.streamName() == "my-stream"
 * // req.shardCount() == 2
 * ```
 */
inline fun createStreamRequestOf(
    streamName: String,
    shardCount: Int = 1,
    builder: CreateStreamRequest.Builder.() -> Unit = {},
): CreateStreamRequest {
    streamName.requireNotBlank("streamName")
    shardCount.validateKinesisShardCount("shardCount")
    return createStreamRequest {
        streamName(streamName)
        shardCount(shardCount)
        builder()
    }
}
