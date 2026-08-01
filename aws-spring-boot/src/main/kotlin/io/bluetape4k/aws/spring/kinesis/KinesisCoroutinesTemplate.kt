package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.aws.kinesis.createStream
import io.bluetape4k.aws.kinesis.deleteStream
import io.bluetape4k.aws.kinesis.describeStream
import io.bluetape4k.aws.kinesis.getRecords
import io.bluetape4k.aws.kinesis.putRecord
import io.bluetape4k.aws.kinesis.putRecords
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.CreateStreamResponse
import software.amazon.awssdk.services.kinesis.model.DeleteStreamResponse
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse
import software.amazon.awssdk.services.kinesis.model.ExpiredIteratorException
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse
import software.amazon.awssdk.services.kinesis.model.KinesisException
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse
import software.amazon.awssdk.services.kinesis.model.Record
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType
import java.time.Duration
import kotlin.random.Random

/**
 * AWS SDK v2 [KinesisAsyncClient]를 사용하는 코루틴 친화적인 [KinesisOperations] 구현입니다.
 *
 * ## 계약
 *
 * `CompletableFuture` Kinesis API를 suspend 함수로 감싸고 [KinesisProperties]의 구성된 스트림
 * 속성을 적용하며, 명시적인 폴링 사용 사례를 위한 단일 샤드 cold [Flow]를 제공합니다.
 */
class KinesisCoroutinesTemplate(
    private val kinesisAsyncClient: KinesisAsyncClient,
    private val properties: KinesisProperties,
) : KinesisOperations {

    override suspend fun createStream(streamName: String, shardCount: Int): CreateStreamResponse {
        require(shardCount >= 1) { "shardCount must be greater than or equal to 1." }
        return kinesisAsyncClient.createStream(streamName, shardCount)
    }

    override suspend fun createConfiguredStream(streamName: String): CreateStreamResponse {
        streamName.requireNotBlank("streamName")
        val stream = properties.streams[streamName]
            ?: throw IllegalArgumentException("Stream '$streamName' is not configured.")
        return createStream(streamName, stream.shardCount)
    }

    override suspend fun deleteStream(streamName: String): DeleteStreamResponse =
        kinesisAsyncClient.deleteStream(streamName)

    override suspend fun describeStream(streamName: String): DescribeStreamResponse =
        kinesisAsyncClient.describeStream(streamName)

    override suspend fun putRecord(request: KinesisPutRecordRequest): PutRecordResponse =
        kinesisAsyncClient.putRecord(
            streamName = request.streamName,
            partitionKey = request.partitionKey,
            data = request.data,
        )

    override suspend fun putRecords(
        streamName: String,
        entries: List<PutRecordsRequestEntry>,
    ): PutRecordsResponse {
        require(entries.isNotEmpty()) { "entries must not be empty." }
        return kinesisAsyncClient.putRecords(streamName, entries)
    }

    override suspend fun getShardIterator(request: KinesisShardIteratorRequest): GetShardIteratorResponse {
        request.streamName.requireNotBlank("streamName")
        request.shardId.requireNotBlank("shardId")
        return kinesisAsyncClient.getShardIterator(
            GetShardIteratorRequest.builder()
                .streamName(request.streamName)
                .shardId(request.shardId)
                .shardIteratorType(request.type)
                .apply {
                    request.startingSequenceNumber?.let(::startingSequenceNumber)
                }
                .build()
        ).await()
    }

    override suspend fun getRecords(shardIterator: String, limit: Int): GetRecordsResponse =
        kinesisAsyncClient.getRecords(shardIterator, limit)

    override fun recordFlow(request: KinesisRecordFlowRequest): Flow<Record> = flow {
        request.streamName.requireNotBlank("streamName")
        request.shardId.requireNotBlank("shardId")

        val options = request.options ?: properties.consumer.toFlowOptions()
        var currentPosition = request.position
        var lastSeenSequenceNumber: String? = null
        var iteratorRetryCount = 0
        var throttleRetryCount = 0
        var shardIterator: String? = null

        while (true) {
            currentCoroutineContext().ensureActive()

            try {
                if (shardIterator == null) {
                    shardIterator = fetchShardIterator(request.streamName, request.shardId, currentPosition)
                }
                val currentShardIterator = requireNotNull(shardIterator) {
                    "shardIterator must be initialized before GetRecords."
                }

                val response = kinesisAsyncClient.getRecords(
                    GetRecordsRequest.builder()
                        .shardIterator(currentShardIterator)
                        .limit(options.batchLimit)
                        .build()
                ).await()

                iteratorRetryCount = 0
                throttleRetryCount = 0

                for (record in response.records().orEmpty()) {
                    emit(record)
                    lastSeenSequenceNumber = record.sequenceNumber()
                }

                val nextShardIterator = response.nextShardIterator() ?: return@flow
                shardIterator = nextShardIterator

                val pollDelay = if (response.records().orEmpty().isEmpty()) {
                    options.emptyBackoff
                } else {
                    options.pollInterval
                }
                delay(pollDelay.toMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExpiredIteratorException) {
                iteratorRetryCount++
                if (iteratorRetryCount > options.maxIteratorRetries) {
                    throw e
                }

                val lastSeen = lastSeenSequenceNumber
                if (lastSeen == null && currentPosition is KinesisStartingPosition.Latest) {
                    throw e
                }

                currentPosition = lastSeen
                    ?.let { KinesisStartingPosition.AfterSequenceNumber(it) }
                    ?: currentPosition
                shardIterator = null
            } catch (e: KinesisException) {
                if (!e.isThrottlingException) throw e

                throttleRetryCount++
                if (throttleRetryCount > options.maxThrottleRetries) {
                    throw e
                }

                delay(jitteredBackoff(throttleRetryCount, options).toMillis())
            }
        }
    }

    private suspend fun fetchShardIterator(
        streamName: String,
        shardId: String,
        position: KinesisStartingPosition,
    ): String {
        val response = kinesisAsyncClient.getShardIterator(
            GetShardIteratorRequest.builder()
                .streamName(streamName)
                .shardId(shardId)
                .applyPosition(position)
                .build()
        ).await()
        return response.shardIterator()
            ?: error("getShardIterator returned null iterator for stream=$streamName shard=$shardId")
    }

    private fun GetShardIteratorRequest.Builder.applyPosition(
        position: KinesisStartingPosition,
    ): GetShardIteratorRequest.Builder = apply {
        when (position) {
            KinesisStartingPosition.TrimHorizon -> shardIteratorType(ShardIteratorType.TRIM_HORIZON)
            KinesisStartingPosition.Latest -> shardIteratorType(ShardIteratorType.LATEST)
            is KinesisStartingPosition.AtSequenceNumber -> {
                shardIteratorType(ShardIteratorType.AT_SEQUENCE_NUMBER)
                startingSequenceNumber(position.sequenceNumber)
            }
            is KinesisStartingPosition.AfterSequenceNumber -> {
                shardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER)
                startingSequenceNumber(position.sequenceNumber)
            }
            is KinesisStartingPosition.AtTimestamp -> {
                shardIteratorType(ShardIteratorType.AT_TIMESTAMP)
                timestamp(position.timestamp)
            }
        }
    }

    private fun jitteredBackoff(attempt: Int, options: KinesisRecordFlowOptions): Duration {
        val maxMs = options.maxThrottleBackoff.toMillis()
        if (maxMs <= 0L) return Duration.ZERO

        val initialMs = options.initialThrottleBackoff.toMillis().coerceAtLeast(0L)
        val shift = (attempt - 1).coerceAtMost(30)
        val baseMs = if (shift > 0 && initialMs > (Long.MAX_VALUE ushr shift)) {
            maxMs
        } else {
            (initialMs shl shift).coerceAtMost(maxMs)
        }
        if (baseMs <= 0L || options.jitterRatio == 0.0) {
            return Duration.ofMillis(baseMs)
        }

        val floor = (baseMs * (1.0 - options.jitterRatio)).toLong().coerceAtLeast(0L)
        val jittered = Random.Default.nextLong(floor, baseMs + 1)
        return Duration.ofMillis(jittered)
    }
}
