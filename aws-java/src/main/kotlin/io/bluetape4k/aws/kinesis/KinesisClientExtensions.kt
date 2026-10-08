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
 * Kinesis 스트림을 생성합니다.
 *
 * ## 동작/계약
 * - [streamName]이 blank이면 `IllegalArgumentException`을 던진다.
 * - [shardCount]는 1 이상이어야 한다.
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
    val request = createStreamRequest {
        streamName(streamName)
        shardCount(shardCount)
    }
    return createStream(request)
}

/**
 * Kinesis 스트림에 단일 레코드를 전송합니다.
 *
 * ## 동작/계약
 * - [streamName]이 blank이면 `IllegalArgumentException`을 던진다.
 * - [partitionKey]가 blank이면 `IllegalArgumentException`을 던진다.
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
    val request = putRecordRequest {
        streamName(streamName)
        partitionKey(partitionKey)
        data(data)
    }
    return putRecord(request)
}

/**
 * Kinesis 스트림에 복수의 레코드를 배치로 전송합니다.
 *
 * ## 동작/계약
 * - [streamName]이 blank이면 `IllegalArgumentException`을 던진다.
 * - [entries]는 1..500개여야 한다.
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

    val request = putRecordsRequest {
        streamName(streamName)
        records(entries)
    }
    return putRecords(request)
}

/**
 * Kinesis 스트림의 샤드 이터레이터를 조회합니다.
 *
 * ## 동작/계약
 * - [streamName]이 blank이면 `IllegalArgumentException`을 던진다.
 * - [shardId]가 blank이면 `IllegalArgumentException`을 던진다.
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
    val request = getShardIteratorRequest {
        streamName(streamName)
        shardId(shardId)
        shardIteratorType(type)
    }
    return getShardIterator(request)
}

/**
 * Kinesis 샤드 이터레이터로부터 레코드를 조회합니다.
 *
 * ## 동작/계약
 * - [shardIterator]가 blank이면 `IllegalArgumentException`을 던진다.
 * - [limit]은 1..10000이어야 한다.
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
    val request = getRecordsRequest {
        shardIterator(shardIterator)
        limit(limit)
    }
    return getRecords(request)
}

/**
 * Kinesis 스트림의 상세 정보를 조회합니다.
 *
 * ## 동작/계약
 * - [streamName]이 blank이면 `IllegalArgumentException`을 던진다.
 *
 * ```kotlin
 * val response = kinesisClient.describeStream("my-stream")
 * ```
 */
fun KinesisClient.describeStream(
    streamName: String,
): DescribeStreamResponse {
    streamName.requireNotBlank("streamName")
    val request = describeStreamRequest {
        streamName(streamName)
    }
    return describeStream(request)
}

/**
 * Kinesis 스트림을 삭제합니다.
 *
 * ## 동작/계약
 * - [streamName]이 blank이면 `IllegalArgumentException`을 던진다.
 *
 * ```kotlin
 * val response = kinesisClient.deleteStream("my-stream")
 * ```
 */
fun KinesisClient.deleteStream(
    streamName: String,
): DeleteStreamResponse {
    streamName.requireNotBlank("streamName")
    val request = deleteStreamRequest {
        streamName(streamName)
    }
    return deleteStream(request)
}
