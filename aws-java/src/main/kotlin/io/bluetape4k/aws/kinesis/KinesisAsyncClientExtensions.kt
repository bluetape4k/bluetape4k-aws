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
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.CreateStreamResponse
import software.amazon.awssdk.services.kinesis.model.DeleteStreamResponse
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType
import java.util.concurrent.CompletableFuture

/**
 * Creates a Kinesis stream asynchronously.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.createStreamAsync("my-stream", shardCount = 1).join()
 * ```
 */
fun KinesisAsyncClient.createStreamAsync(
    streamName: String,
    shardCount: Int = 1,
): CompletableFuture<CreateStreamResponse> {
    streamName.requireNotBlank("streamName")
    shardCount.validateKinesisShardCount("shardCount")
    return createStream(createStreamRequest {
        streamName(streamName)
        shardCount(shardCount)
    })
}

/**
 * Sends a single record to a Kinesis stream asynchronously.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.putRecordAsync(
 *     streamName = "my-stream",
 *     partitionKey = "pk",
 *     data = SdkBytes.fromUtf8String("data")
 * ).join()
 * ```
 */
fun KinesisAsyncClient.putRecordAsync(
    streamName: String,
    partitionKey: String,
    data: SdkBytes,
): CompletableFuture<PutRecordResponse> {
    streamName.requireNotBlank("streamName")
    partitionKey.requireNotBlank("partitionKey")
    return putRecord(putRecordRequest {
        streamName(streamName)
        partitionKey(partitionKey)
        data(data)
    })
}

/**
 * Sends multiple records to a Kinesis stream asynchronously as a batch.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.putRecordsAsync("my-stream", entries).join()
 * ```
 */
fun KinesisAsyncClient.putRecordsAsync(
    streamName: String,
    entries: List<PutRecordsRequestEntry>,
): CompletableFuture<PutRecordsResponse> {
    streamName.requireNotBlank("streamName")
    entries.validateKinesisPutRecordsEntries("entries")
    return putRecords(putRecordsRequest {
        streamName(streamName)
        records(entries)
    })
}

/**
 * Gets a shard iterator for a Kinesis stream asynchronously.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.getShardIteratorAsync(
 *     streamName = "my-stream",
 *     shardId = "shardId-000000000000",
 *     type = ShardIteratorType.TRIM_HORIZON
 * ).join()
 * ```
 */
fun KinesisAsyncClient.getShardIteratorAsync(
    streamName: String,
    shardId: String,
    type: ShardIteratorType = ShardIteratorType.TRIM_HORIZON,
): CompletableFuture<GetShardIteratorResponse> {
    streamName.requireNotBlank("streamName")
    shardId.requireNotBlank("shardId")
    return getShardIterator(getShardIteratorRequest {
        streamName(streamName)
        shardId(shardId)
        shardIteratorType(type)
    })
}

/**
 * Gets records from a Kinesis shard iterator asynchronously.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.getRecordsAsync(shardIterator, limit = 100).join()
 * ```
 */
fun KinesisAsyncClient.getRecordsAsync(
    shardIterator: String,
    limit: Int = 100,
): CompletableFuture<GetRecordsResponse> {
    shardIterator.requireNotBlank("shardIterator")
    limit.validateKinesisGetRecordsLimit("limit")
    return getRecords(getRecordsRequest {
        shardIterator(shardIterator)
        limit(limit)
    })
}

/**
 * Describes a Kinesis stream asynchronously.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.describeStreamAsync("my-stream").join()
 * ```
 */
fun KinesisAsyncClient.describeStreamAsync(
    streamName: String,
): CompletableFuture<DescribeStreamResponse> {
    streamName.requireNotBlank("streamName")
    return describeStream(describeStreamRequest {
        streamName(streamName)
    })
}

/**
 * Deletes a Kinesis stream asynchronously.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.deleteStreamAsync("my-stream").join()
 * ```
 */
fun KinesisAsyncClient.deleteStreamAsync(
    streamName: String,
): CompletableFuture<DeleteStreamResponse> {
    streamName.requireNotBlank("streamName")
    return deleteStream(deleteStreamRequest {
        streamName(streamName)
    })
}
