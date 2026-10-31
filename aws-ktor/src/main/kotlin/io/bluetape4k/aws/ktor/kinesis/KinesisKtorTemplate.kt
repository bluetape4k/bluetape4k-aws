package io.bluetape4k.aws.ktor.kinesis

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.model.CreateStreamResponse
import aws.sdk.kotlin.services.kinesis.model.DeleteStreamResponse
import aws.sdk.kotlin.services.kinesis.model.DescribeStreamResponse
import aws.sdk.kotlin.services.kinesis.model.ExpiredIteratorException
import aws.sdk.kotlin.services.kinesis.model.GetRecordsResponse
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorResponse
import aws.sdk.kotlin.services.kinesis.model.KinesisException
import aws.sdk.kotlin.services.kinesis.model.ProvisionedThroughputExceededException
import aws.sdk.kotlin.services.kinesis.model.PutRecordResponse
import aws.sdk.kotlin.services.kinesis.model.PutRecordsRequestEntry
import aws.sdk.kotlin.services.kinesis.model.PutRecordsResponse
import aws.sdk.kotlin.services.kinesis.model.Record
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import io.bluetape4k.aws.kotlin.kinesis.createStream
import io.bluetape4k.aws.kotlin.kinesis.deleteStream
import io.bluetape4k.aws.kotlin.kinesis.describeStream
import io.bluetape4k.aws.kotlin.kinesis.getRecords
import io.bluetape4k.aws.kotlin.kinesis.getShardIterator
import io.bluetape4k.aws.kotlin.kinesis.putRecord
import io.bluetape4k.aws.kotlin.kinesis.putRecords
import io.bluetape4k.javatimes.millis
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import kotlin.random.Random
import kotlin.time.toKotlinDuration
import aws.smithy.kotlin.runtime.time.Instant as SmithyInstant

/**
 * AWS Kotlin SDK [KinesisClient]를 사용하는 코루틴 중심 [KinesisKtorOperations] 구현입니다.
 *
 * 단순 작업은 `aws-kotlin` 모듈의 suspend 확장을 재사용합니다. 단일 샤드 cold Flow는 Ktor
 * 옵션의 retry와 polling 계약을 보존하도록 이 모듈에서 실행합니다.
 */
class KinesisKtorTemplate(
    private val kinesisClient: KinesisClient,
    private val streams: Map<String, KinesisKtorStream> = emptyMap(),
): KinesisKtorOperations {

    override suspend fun createStream(streamName: String, shardCount: Int): CreateStreamResponse {
        shardCount.requirePositiveNumber("shardCount")
        return kinesisClient.createStream(streamName, shardCount)
    }

    override suspend fun createConfiguredStream(streamName: String): CreateStreamResponse {
        streamName.requireNotBlank("streamName")
        val stream = streams[streamName]
            ?: throw IllegalArgumentException("Stream '$streamName' is not configured.")
        return createStream(streamName, stream.shardCount)
    }

    override suspend fun deleteStream(streamName: String): DeleteStreamResponse =
        kinesisClient.deleteStream(streamName)

    override suspend fun describeStream(streamName: String): DescribeStreamResponse =
        kinesisClient.describeStream(streamName)

    override suspend fun putRecord(request: KinesisPutRecordRequest): PutRecordResponse =
        kinesisClient.putRecord(
            streamName = request.streamName,
            partitionKey = request.partitionKey,
            data = request.data,
        )

    override suspend fun putRecords(
        streamName: String,
        entries: List<PutRecordsRequestEntry>,
    ): PutRecordsResponse {
        entries.requireNotEmpty("entries")
        return kinesisClient.putRecords(streamName, entries)
    }

    override suspend fun getShardIterator(request: KinesisShardIteratorRequest): GetShardIteratorResponse =
        kinesisClient.getShardIterator(
            streamName = request.streamName,
            shardId = request.shardId,
            type = request.type,
            builder = {
                request.startingSequenceNumber?.let { startingSequenceNumber = it }
            },
        )

    override suspend fun getRecords(shardIterator: String, limit: Int): GetRecordsResponse =
        kinesisClient.getRecords(shardIterator, limit)

    override fun recordFlow(request: KinesisRecordFlowRequest): Flow<Record> = flow {
        val options = request.options
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

                val response = kinesisClient.getRecords(currentShardIterator, options.batchLimit)
                iteratorRetryCount = 0
                throttleRetryCount = 0

                val records = response.records.orEmpty()
                for (record in records) {
                    emit(record)
                    lastSeenSequenceNumber = record.sequenceNumber
                }

                val nextShardIterator = response.nextShardIterator ?: return@flow
                shardIterator = nextShardIterator

                val pollDelay = if (records.isEmpty()) options.emptyBackoff else options.pollInterval
                delay(pollDelay.toKotlinDuration())
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
                if (!e.isKinesisThrottlingException()) throw e

                throttleRetryCount++
                if (throttleRetryCount > options.maxThrottleRetries) {
                    throw e
                }

                delay(jitteredBackoff(throttleRetryCount, options).toKotlinDuration())
            }
        }
    }

    private suspend fun fetchShardIterator(
        streamName: String,
        shardId: String,
        position: KinesisStartingPosition,
    ): String {
        val response = kinesisClient.getShardIterator(
            streamName = streamName,
            shardId = shardId,
            type = position.shardIteratorType(),
            builder = {
                when (position) {
                    is KinesisStartingPosition.AtSequenceNumber -> startingSequenceNumber = position.sequenceNumber
                    is KinesisStartingPosition.AfterSequenceNumber -> startingSequenceNumber = position.sequenceNumber
                    is KinesisStartingPosition.AtTimestamp -> timestamp =
                        SmithyInstant.fromEpochSeconds(position.timestamp.epochSecond, position.timestamp.nano)
                    KinesisStartingPosition.TrimHorizon,
                    KinesisStartingPosition.Latest -> Unit
                }
            },
        )
        return response.shardIterator
            ?: error("getShardIterator returned null iterator for stream=$streamName shard=$shardId")
    }

    private fun KinesisStartingPosition.shardIteratorType(): ShardIteratorType =
        when (this) {
            KinesisStartingPosition.TrimHorizon -> ShardIteratorType.TrimHorizon
            KinesisStartingPosition.Latest -> ShardIteratorType.Latest
            is KinesisStartingPosition.AtSequenceNumber -> ShardIteratorType.AtSequenceNumber
            is KinesisStartingPosition.AfterSequenceNumber -> ShardIteratorType.AfterSequenceNumber
            is KinesisStartingPosition.AtTimestamp -> ShardIteratorType.AtTimestamp
        }

    private fun KinesisException.isKinesisThrottlingException(): Boolean =
        this is ProvisionedThroughputExceededException ||
            (sdkErrorMetadata.protocolResponse as? HttpResponse)?.status?.value == 429 ||
            sdkErrorMetadata.errorCode in KINESIS_THROTTLING_ERROR_CODES

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
            return baseMs.millis()
        }

        val floor = (baseMs * (1.0 - options.jitterRatio)).toLong().coerceAtLeast(0L)
        return Random.nextLong(floor, baseMs + 1).millis()
    }

    private companion object {
        val KINESIS_THROTTLING_ERROR_CODES = setOf(
            "BandwidthLimitExceeded",
            "EC2ThrottledException",
            "ProvisionedThroughputExceededException",
            "RequestLimitExceeded",
            "RequestThrottled",
            "RequestThrottledException",
            "SlowDown",
            "ThrottledException",
            "Throttling",
            "ThrottlingException",
            "TooManyRequestsException",
            "TransactionInProgressException",
        )
    }
}
