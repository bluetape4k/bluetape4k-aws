package io.bluetape4k.aws.kinesis.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType

/**
 * Builds a [GetShardIteratorRequest] with a DSL block.
 *
 * ```kotlin
 * val req = getShardIteratorRequest {
 *     streamName("my-stream")
 *     shardId("shardId-000000000000")
 *     shardIteratorType(ShardIteratorType.TRIM_HORIZON)
 * }
 * ```
 */
inline fun getShardIteratorRequest(
    builder: GetShardIteratorRequest.Builder.() -> Unit,
): GetShardIteratorRequest =
    GetShardIteratorRequest.builder().apply(builder).build()

/**
 * Creates a [GetShardIteratorRequest] from a stream name, shard ID, and iterator type.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 * - Throws `IllegalArgumentException` when [shardId] is blank.
 *
 * ```kotlin
 * val req = getShardIteratorRequestOf(
 *     streamName = "my-stream",
 *     shardId = "shardId-000000000000",
 *     type = ShardIteratorType.TRIM_HORIZON
 * )
 * ```
 */
inline fun getShardIteratorRequestOf(
    streamName: String,
    shardId: String,
    type: ShardIteratorType = ShardIteratorType.TRIM_HORIZON,
    builder: GetShardIteratorRequest.Builder.() -> Unit = {},
): GetShardIteratorRequest {
    streamName.requireNotBlank("streamName")
    shardId.requireNotBlank("shardId")
    return getShardIteratorRequest {
        streamName(streamName)
        shardId(shardId)
        shardIteratorType(type)
        builder()
    }
}
