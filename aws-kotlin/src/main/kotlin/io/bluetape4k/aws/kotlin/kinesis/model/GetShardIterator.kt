package io.bluetape4k.aws.kotlin.kinesis.model

import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [GetShardIteratorRequest] from a stream name, shard ID, and iterator type.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 * - Throws `IllegalArgumentException` when [shardId] is blank.
 *
 * ```kotlin
 * val req = getShardIteratorRequestOf(
 *     streamName = "my-stream",
 *     shardId = "shardId-000000000000",
 *     type = ShardIteratorType.TrimHorizon
 * )
 * ```
 */
inline fun getShardIteratorRequestOf(
    streamName: String,
    shardId: String,
    type: ShardIteratorType = ShardIteratorType.TrimHorizon,
    crossinline builder: GetShardIteratorRequest.Builder.() -> Unit = {},
): GetShardIteratorRequest {
    streamName.requireNotBlank("streamName")
    shardId.requireNotBlank("shardId")
    return GetShardIteratorRequest {
        this.streamName = streamName
        this.shardId = shardId
        this.shardIteratorType = type
        builder()
    }
}
