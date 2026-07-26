package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.createStream
import aws.sdk.kotlin.services.kinesis.deleteStream
import aws.sdk.kotlin.services.kinesis.describeStream
import aws.sdk.kotlin.services.kinesis.getRecords
import aws.sdk.kotlin.services.kinesis.getShardIterator
import aws.sdk.kotlin.services.kinesis.model.CreateStreamRequest
import aws.sdk.kotlin.services.kinesis.model.CreateStreamResponse
import aws.sdk.kotlin.services.kinesis.model.DeleteStreamRequest
import aws.sdk.kotlin.services.kinesis.model.DeleteStreamResponse
import aws.sdk.kotlin.services.kinesis.model.DescribeStreamRequest
import aws.sdk.kotlin.services.kinesis.model.DescribeStreamResponse
import aws.sdk.kotlin.services.kinesis.model.GetRecordsRequest
import aws.sdk.kotlin.services.kinesis.model.GetRecordsResponse
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorResponse
import aws.sdk.kotlin.services.kinesis.model.PutRecordRequest
import aws.sdk.kotlin.services.kinesis.model.PutRecordResponse
import aws.sdk.kotlin.services.kinesis.model.PutRecordsRequest
import aws.sdk.kotlin.services.kinesis.model.PutRecordsRequestEntry
import aws.sdk.kotlin.services.kinesis.model.PutRecordsResponse
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import aws.sdk.kotlin.services.kinesis.putRecord
import aws.sdk.kotlin.services.kinesis.putRecords
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a Kinesis stream.
 *
 * ```kotlin
 * val response = kinesisClient.createStream("my-stream", shardCount = 1)
 * ```
 *
 * @param streamName name of the stream to create
 * @param shardCount number of shards; defaults to 1
 * @param builder configures the [CreateStreamRequest]
 * @return the [CreateStreamResponse]
 */
suspend inline fun KinesisClient.createStream(
    streamName: String,
    shardCount: Int = 1,
    crossinline builder: CreateStreamRequest.Builder.() -> Unit = {},
): CreateStreamResponse {
    streamName.requireNotBlank("streamName")
    return createStream {
        this.streamName = streamName
        this.shardCount = shardCount
        builder()
    }
}

/**
 * Puts a single record into a Kinesis stream.
 *
 * ```kotlin
 * val response = kinesisClient.putRecord(
 *     streamName = "my-stream",
 *     partitionKey = "pk",
 *     data = "hello".toByteArray()
 * )
 * ```
 *
 * @param streamName target stream name
 * @param partitionKey partition key
 * @param data data bytes to send
 * @param builder configures the [PutRecordRequest]
 * @return the [PutRecordResponse]
 */
suspend inline fun KinesisClient.putRecord(
    streamName: String,
    partitionKey: String,
    data: ByteArray,
    crossinline builder: PutRecordRequest.Builder.() -> Unit = {},
): PutRecordResponse {
    streamName.requireNotBlank("streamName")
    partitionKey.requireNotBlank("partitionKey")
    return putRecord {
        this.streamName = streamName
        this.partitionKey = partitionKey
        this.data = data
        builder()
    }
}

/**
 * Puts multiple records into a Kinesis stream as a batch.
 *
 * ```kotlin
 * val entries = listOf(
 *     PutRecordsRequestEntry { partitionKey = "pk1"; data = "msg1".toByteArray() }
 * )
 * val response = kinesisClient.putRecords("my-stream", entries)
 * ```
 *
 * @param streamName target stream name
 * @param entries records to send
 * @param builder configures the [PutRecordsRequest]
 * @return the [PutRecordsResponse]
 */
suspend inline fun KinesisClient.putRecords(
    streamName: String,
    entries: List<PutRecordsRequestEntry>,
    crossinline builder: PutRecordsRequest.Builder.() -> Unit = {},
): PutRecordsResponse {
    streamName.requireNotBlank("streamName")
    return putRecords {
        this.streamName = streamName
        this.records = entries
        builder()
    }
}

/**
 * Retrieves a shard iterator for a Kinesis stream.
 *
 * ```kotlin
 * val response = kinesisClient.getShardIterator(
 *     streamName = "my-stream",
 *     shardId = "shardId-000000000000",
 *     type = ShardIteratorType.TrimHorizon
 * )
 * ```
 *
 * @param streamName stream name
 * @param shardId shard ID
 * @param type shard iterator type; defaults to [ShardIteratorType.TrimHorizon]
 * @param builder configures the [GetShardIteratorRequest]
 * @return the [GetShardIteratorResponse]
 */
suspend inline fun KinesisClient.getShardIterator(
    streamName: String,
    shardId: String,
    type: ShardIteratorType = ShardIteratorType.TrimHorizon,
    crossinline builder: GetShardIteratorRequest.Builder.() -> Unit = {},
): GetShardIteratorResponse {
    streamName.requireNotBlank("streamName")
    shardId.requireNotBlank("shardId")
    return getShardIterator {
        this.streamName = streamName
        this.shardId = shardId
        this.shardIteratorType = type
        builder()
    }
}

/**
 * Retrieves records from a Kinesis shard iterator.
 *
 * ```kotlin
 * val response = kinesisClient.getRecords(shardIterator, limit = 100)
 * ```
 *
 * @param shardIterator shard iterator string
 * @param limit maximum number of records to retrieve; defaults to 100
 * @param builder configures the [GetRecordsRequest]
 * @return the [GetRecordsResponse]
 */
suspend inline fun KinesisClient.getRecords(
    shardIterator: String,
    limit: Int = 100,
    crossinline builder: GetRecordsRequest.Builder.() -> Unit = {},
): GetRecordsResponse {
    shardIterator.requireNotBlank("shardIterator")
    return getRecords {
        this.shardIterator = shardIterator
        this.limit = limit
        builder()
    }
}

/**
 * Retrieves details about a Kinesis stream.
 *
 * ```kotlin
 * val response = kinesisClient.describeStream("my-stream")
 * ```
 *
 * @param streamName stream name
 * @param builder configures the [DescribeStreamRequest]
 * @return the [DescribeStreamResponse]
 */
suspend inline fun KinesisClient.describeStream(
    streamName: String,
    crossinline builder: DescribeStreamRequest.Builder.() -> Unit = {},
): DescribeStreamResponse {
    streamName.requireNotBlank("streamName")
    return describeStream {
        this.streamName = streamName
        builder()
    }
}

/**
 * Deletes a Kinesis stream.
 *
 * ```kotlin
 * val response = kinesisClient.deleteStream("my-stream")
 * ```
 *
 * @param streamName name of the stream to delete
 * @param builder configures the [DeleteStreamRequest]
 * @return the [DeleteStreamResponse]
 */
suspend inline fun KinesisClient.deleteStream(
    streamName: String,
    crossinline builder: DeleteStreamRequest.Builder.() -> Unit = {},
): DeleteStreamResponse {
    streamName.requireNotBlank("streamName")
    return deleteStream {
        this.streamName = streamName
        builder()
    }
}
