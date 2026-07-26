package io.bluetape4k.aws.kinesis

import kotlinx.coroutines.future.await
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

/**
 * Creates a Kinesis stream with coroutines.
 *
 * Internally calls [createStreamAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.createStream("my-stream", shardCount = 1)
 * ```
 */
suspend fun KinesisAsyncClient.createStream(
    streamName: String,
    shardCount: Int = 1,
): CreateStreamResponse =
    createStreamAsync(streamName, shardCount).await()

/**
 * Sends a single record to a Kinesis stream with coroutines.
 *
 * Internally calls [putRecordAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.putRecord(
 *     streamName = "my-stream",
 *     partitionKey = "pk",
 *     data = SdkBytes.fromUtf8String("hello")
 * )
 * ```
 */
suspend fun KinesisAsyncClient.putRecord(
    streamName: String,
    partitionKey: String,
    data: SdkBytes,
): PutRecordResponse =
    putRecordAsync(streamName, partitionKey, data).await()

/**
 * Sends multiple records to a Kinesis stream as a batch with coroutines.
 *
 * Internally calls [putRecordsAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.putRecords("my-stream", entries)
 * ```
 */
suspend fun KinesisAsyncClient.putRecords(
    streamName: String,
    entries: List<PutRecordsRequestEntry>,
): PutRecordsResponse =
    putRecordsAsync(streamName, entries).await()

/**
 * Gets a shard iterator for a Kinesis stream with coroutines.
 *
 * Internally calls [getShardIteratorAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.getShardIterator(
 *     streamName = "my-stream",
 *     shardId = "shardId-000000000000",
 *     type = ShardIteratorType.TRIM_HORIZON
 * )
 * ```
 */
suspend fun KinesisAsyncClient.getShardIterator(
    streamName: String,
    shardId: String,
    type: ShardIteratorType = ShardIteratorType.TRIM_HORIZON,
): GetShardIteratorResponse =
    getShardIteratorAsync(streamName, shardId, type).await()

/**
 * Gets records from a Kinesis shard iterator with coroutines.
 *
 * Internally calls [getRecordsAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.getRecords(shardIterator, limit = 100)
 * ```
 */
suspend fun KinesisAsyncClient.getRecords(
    shardIterator: String,
    limit: Int = 100,
): GetRecordsResponse =
    getRecordsAsync(shardIterator, limit).await()

/**
 * Describes a Kinesis stream with coroutines.
 *
 * Internally calls [describeStreamAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.describeStream("my-stream")
 * ```
 */
suspend fun KinesisAsyncClient.describeStream(
    streamName: String,
): DescribeStreamResponse =
    describeStreamAsync(streamName).await()

/**
 * Deletes a Kinesis stream with coroutines.
 *
 * Internally calls [deleteStreamAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = kinesisAsyncClient.deleteStream("my-stream")
 * ```
 */
suspend fun KinesisAsyncClient.deleteStream(
    streamName: String,
): DeleteStreamResponse =
    deleteStreamAsync(streamName).await()
