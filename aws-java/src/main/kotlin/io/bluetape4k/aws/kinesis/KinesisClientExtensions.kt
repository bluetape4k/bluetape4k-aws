package io.bluetape4k.aws.kinesis

import io.bluetape4k.aws.kinesis.model.createStreamRequest
import io.bluetape4k.aws.kinesis.model.deleteStreamRequest
import io.bluetape4k.aws.kinesis.model.describeStreamRequest
import io.bluetape4k.aws.kinesis.model.getRecordsRequest
import io.bluetape4k.aws.kinesis.model.getShardIteratorRequest
import io.bluetape4k.aws.kinesis.model.putRecordRequest
import io.bluetape4k.aws.kinesis.model.putRecordsRequest
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.model.CreateStreamResponse
import software.amazon.awssdk.services.kinesis.model.DeleteStreamResponse
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType

/**
 * Creates a Kinesis stream.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 * - [shardCount] must be at least 1.
 *
 * ```kotlin
 * val response = kinesisClient.createStream("my-stream", shardCount = 1)
 * ```
 */
fun KinesisClient.createStream(
    streamName: String,
    shardCount: Int = 1,
): CreateStreamResponse {
    streamName.requireNotBlank("streamName")
    shardCount.validateKinesisShardCount("shardCount")
    return createStream(createStreamRequest {
        streamName(streamName)
        shardCount(shardCount)
    })
}

/**
 * Sends a single record to a Kinesis stream.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 * - Throws `IllegalArgumentException` when [partitionKey] is blank.
 *
 * ```kotlin
 * val response = kinesisClient.putRecord(
 *     streamName = "my-stream",
 *     partitionKey = "partition-1",
 *     data = SdkBytes.fromUtf8String("hello world")
 * )
 * // response.sequenceNumber().isNotBlank() == true
 * ```
 */
fun KinesisClient.putRecord(
    streamName: String,
    partitionKey: String,
    data: SdkBytes,
): PutRecordResponse {
    streamName.requireNotBlank("streamName")
    partitionKey.requireNotBlank("partitionKey")
    return putRecord(putRecordRequest {
        streamName(streamName)
        partitionKey(partitionKey)
        data(data)
    })
}

/**
 * Sends multiple records to a Kinesis stream as a batch.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 * - [entries] must contain 1..500 entries.
 *
 * ```kotlin
 * val entries = listOf(
 *     PutRecordsRequestEntry.builder()
 *         .partitionKey("pk1")
 *         .data(SdkBytes.fromUtf8String("msg1"))
 *         .build()
 * )
 * val response = kinesisClient.putRecords("my-stream", entries)
 * ```
 */
fun KinesisClient.putRecords(
    streamName: String,
    entries: List<PutRecordsRequestEntry>,
): PutRecordsResponse {
    streamName.requireNotBlank("streamName")
    entries.validateKinesisPutRecordsEntries("entries")
    return putRecords(putRecordsRequest {
        streamName(streamName)
        records(entries)
    })
}

/**
 * Gets a shard iterator for a Kinesis stream.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 * - Throws `IllegalArgumentException` when [shardId] is blank.
 *
 * ```kotlin
 * val response = kinesisClient.getShardIterator(
 *     streamName = "my-stream",
 *     shardId = "shardId-000000000000",
 *     type = ShardIteratorType.TRIM_HORIZON
 * )
 * // response.shardIterator().isNotBlank() == true
 * ```
 */
fun KinesisClient.getShardIterator(
    streamName: String,
    shardId: String,
    type: ShardIteratorType = ShardIteratorType.TRIM_HORIZON,
): GetShardIteratorResponse {
    streamName.requireNotBlank("streamName")
    shardId.requireNotBlank("shardId")
    return getShardIterator(getShardIteratorRequest {
        streamName(streamName)
        shardId(shardId)
        shardIteratorType(type)
    })
}

/**
 * Gets records from a Kinesis shard iterator.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [shardIterator] is blank.
 * - [limit] must be in the 1..10000 range.
 *
 * ```kotlin
 * val response = kinesisClient.getRecords(shardIterator, limit = 100)
 * ```
 */
fun KinesisClient.getRecords(
    shardIterator: String,
    limit: Int = 100,
): GetRecordsResponse {
    shardIterator.requireNotBlank("shardIterator")
    limit.validateKinesisGetRecordsLimit("limit")
    return getRecords(getRecordsRequest {
        shardIterator(shardIterator)
        limit(limit)
    })
}

/**
 * Describes a Kinesis stream.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 *
 * ```kotlin
 * val response = kinesisClient.describeStream("my-stream")
 * ```
 */
fun KinesisClient.describeStream(
    streamName: String,
): DescribeStreamResponse {
    streamName.requireNotBlank("streamName")
    return describeStream(describeStreamRequest {
        streamName(streamName)
    })
}

/**
 * Deletes a Kinesis stream.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 *
 * ```kotlin
 * val response = kinesisClient.deleteStream("my-stream")
 * ```
 */
fun KinesisClient.deleteStream(
    streamName: String,
): DeleteStreamResponse {
    streamName.requireNotBlank("streamName")
    return deleteStream(deleteStreamRequest {
        streamName(streamName)
    })
}
