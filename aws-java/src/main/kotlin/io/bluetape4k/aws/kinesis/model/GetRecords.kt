package io.bluetape4k.aws.kinesis.model

import io.bluetape4k.aws.kinesis.validateKinesisGetRecordsLimit
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest

/**
 * Builds a [GetRecordsRequest] with a DSL block.
 *
 * ```kotlin
 * val req = getRecordsRequest {
 *     shardIterator("AAA...")
 *     limit(100)
 * }
 * ```
 */
inline fun getRecordsRequest(
    builder: GetRecordsRequest.Builder.() -> Unit,
): GetRecordsRequest =
    GetRecordsRequest.builder().apply(builder).build()

/**
 * Creates a [GetRecordsRequest] from a shard iterator and limit.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [shardIterator] is blank.
 * - [limit] must be in the 1..10000 range.
 *
 * ```kotlin
 * val req = getRecordsRequestOf(shardIterator = "AAA...", limit = 50)
 * ```
 */
inline fun getRecordsRequestOf(
    shardIterator: String,
    limit: Int = 100,
    builder: GetRecordsRequest.Builder.() -> Unit = {},
): GetRecordsRequest {
    shardIterator.requireNotBlank("shardIterator")
    limit.validateKinesisGetRecordsLimit("limit")
    return getRecordsRequest {
        shardIterator(shardIterator)
        limit(limit)
        builder()
    }
}
