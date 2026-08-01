package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.getRecords
import aws.sdk.kotlin.services.kinesis.getShardIterator
import aws.sdk.kotlin.services.kinesis.model.ExpiredIteratorException
import aws.sdk.kotlin.services.kinesis.model.KinesisException
import aws.sdk.kotlin.services.kinesis.model.Record
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import aws.smithy.kotlin.runtime.time.Instant as SmithyInstant
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * 단일 Kinesis 샤드를 계속 폴링해 각 [Record]를 내보내는 콜드 [Flow]를 반환합니다.
 *
 * Flow는 지정한 샤드에서 [KinesisClient.getRecords]를 호출하고 받은 모든 레코드를 내보낸 뒤
 * 반복자를 전진시키는 작업을 계속 반복합니다. Kinesis가 샤드 종료를 알리면
 * (`nextShardIterator`가 `null`) 반복을 끝냅니다.
 *
 * ## 오류 처리
 *
 * - **[CancellationException]** — 즉시 전파하며 재시도하지 않습니다.
 * - **[ExpiredIteratorException]** — 마지막으로 확인한 시퀀스 번호를 사용해
 *   ([KinesisStartingPosition.AfterSequenceNumber]로) 반복자를 다시 가져옵니다. 아직 확인한 레코드가 없고
 *   시작 위치가 [KinesisStartingPosition.Latest]라면 새 `Latest` 반복자가 5분 TTL 동안 기록된 모든
 *   레코드를 조용히 건너뛸 수 있으므로 오류를 즉시 전파합니다. 복구는 최대
 *   [KinesisRecordFlowOptions.maxIteratorRetries]번 시도합니다.
 * - **재시도 가능한 [KinesisException]**(예: `ProvisionedThroughputExceededException`) — 지수 지터
 *   백오프로 최대 [KinesisRecordFlowOptions.maxThrottleRetries]번 재시도합니다.
 * - **재시도할 수 없는 [KinesisException]** — 즉시 전파합니다.
 *
 * ## 사용 예
 *
 * ```kotlin
 * kinesisClient.recordFlow(
 *     streamName = "my-stream",
 *     shardId = "shardId-000000000000",
 *     position = KinesisStartingPosition.TrimHorizon,
 * ).collect { record ->
 *     println(record.data.decodeToString())
 * }
 * ```
 *
 * @param streamName Kinesis 스트림 이름
 * @param shardId 대상 샤드 식별자
 * @param position 시작 위치. 기본값은 [KinesisStartingPosition.TrimHorizon]입니다.
 * @param options 조정 파라미터. 기본값은 [KinesisRecordFlowOptions]입니다.
 * @return [Record] 값을 내보내는 콜드 [Flow]
 */
fun KinesisClient.recordFlow(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon,
    options: KinesisRecordFlowOptions = KinesisRecordFlowOptions(),
): Flow<Record> = flow {
    var currentPosition = position
    var lastSeenSequenceNumber: String? = null
    var iteratorRetryCount = 0
    var throttleRetryCount = 0
    var shardIterator: String? = null   // null triggers a (re-)fetch on next iteration

    while (true) {
        currentCoroutineContext().ensureActive()

        try {
            if (shardIterator == null) {
                shardIterator = fetchShardIterator(streamName, shardId, currentPosition)
            }
            val currentShardIterator = requireNotNull(shardIterator) {
                "shardIterator must be initialized before GetRecords."
            }

            val response = getRecords {
                limit = options.batchLimit
                this.shardIterator = currentShardIterator
            }

            iteratorRetryCount = 0
            throttleRetryCount = 0

            for (record in response.records) {
                emit(record)
                lastSeenSequenceNumber = record.sequenceNumber
            }

            // nextShardIterator == null이면 shard가 닫힌 상태다(resharding).
            if (response.nextShardIterator == null) return@flow
            shardIterator = response.nextShardIterator

            val pollDelay = if (response.records.isEmpty()) options.emptyBackoff else options.pollInterval
            delay(pollDelay)

        } catch (e: CancellationException) {
            throw e

        } catch (e: ExpiredIteratorException) {
            iteratorRetryCount++
            if (iteratorRetryCount > options.maxIteratorRetries) {
                log.error { "Shard iterator expired after $iteratorRetryCount attempts: stream=$streamName shard=$shardId" }
                throw e
            }

            val lastSeen = lastSeenSequenceNumber
            if (lastSeen == null && currentPosition is KinesisStartingPosition.Latest) {
                // Latest를 다시 조회하면 TTL 구간에 기록된 모든 record를 조용히 건너뛴다.
                log.error {
                    "Iterator expired for Latest position with no checkpoint: " +
                            "stream=$streamName shard=$shardId — cannot recover without data loss"
                }
                throw e
            }

            log.warn {
                "Shard iterator expired (attempt $iteratorRetryCount/${options.maxIteratorRetries}): " +
                        "stream=$streamName shard=$shardId"
            }
            currentPosition = lastSeen
                ?.let { KinesisStartingPosition.AfterSequenceNumber(it) }
                ?: currentPosition
            shardIterator = null   // delegate fetch to next iteration's try-block

        } catch (e: KinesisException) {
            if (!e.sdkErrorMetadata.isRetryable) throw e

            throttleRetryCount++
            if (throttleRetryCount > options.maxThrottleRetries) {
                log.error {
                    "Throttle retries exhausted after $throttleRetryCount attempts: " +
                            "stream=$streamName shard=$shardId error=${e.message}"
                }
                throw e
            }

            log.warn { "Throttle retry $throttleRetryCount/${options.maxThrottleRetries}: stream=$streamName shard=$shardId" }
            val backoff = jitteredBackoff(throttleRetryCount, options)
            delay(backoff)
        }
    }
}

/**
 * 지정한 [position]에 대한 새 샤드 반복자를 가져옵니다.
 *
 * Flow 시작 시와 [ExpiredIteratorException] 복구 후에 호출됩니다. `GetShardIterator` 제한 같은
 * 일시적 오류를 다음 반복에서 같은 수준의 `catch` 절이 처리하도록 호출은 주 `try {}` 블록 안에 있습니다.
 */
private suspend fun KinesisClient.fetchShardIterator(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition,
): String {
    return getShardIterator {
        this.streamName = streamName
        this.shardId = shardId
        when (position) {
            is KinesisStartingPosition.TrimHorizon -> {
                shardIteratorType = ShardIteratorType.TrimHorizon
            }
            is KinesisStartingPosition.Latest -> {
                shardIteratorType = ShardIteratorType.Latest
            }
            is KinesisStartingPosition.AtSequenceNumber -> {
                shardIteratorType = ShardIteratorType.AtSequenceNumber
                startingSequenceNumber = position.sequenceNumber
            }
            is KinesisStartingPosition.AfterSequenceNumber -> {
                shardIteratorType = ShardIteratorType.AfterSequenceNumber
                startingSequenceNumber = position.sequenceNumber
            }
            is KinesisStartingPosition.AtTimestamp -> {
                shardIteratorType = ShardIteratorType.AtTimestamp
                timestamp = SmithyInstant.fromEpochSeconds(
                    position.timestamp.epochSecond,
                    position.timestamp.nano,
                )
            }
        }
    }.shardIterator ?: error(
        "getShardIterator returned null iterator for stream=$streamName shard=$shardId"
    )
}

/**
 * 제한 재시도에 사용할 무작위(full-jitter) 지수 백오프 시간을 계산합니다.
 *
 * 기본 지연은 시도할 때마다 두 배로 늘어나며 [KinesisRecordFlowOptions.maxThrottleBackoff]에서 제한합니다.
 * `[0, base]` 범위의 균등 표본으로 지터를 적용합니다. 시도 횟수가 클 때 [Duration] 오버플로를 피하도록
 * 밀리초(Long) 단위로 계산합니다.
 *
 * @param attempt 1부터 시작하는 재시도 횟수
 * @param options 백오프 파라미터를 제공하는 Flow 옵션
 * @return `[0, maxThrottleBackoff]` 범위의 [Duration]
 */
internal fun jitteredBackoff(attempt: Int, options: KinesisRecordFlowOptions): Duration {
    val maxMs = options.maxThrottleBackoff.inWholeMilliseconds
    val baseMs = options.initialThrottleBackoff.inWholeMilliseconds
    val shift = (attempt - 1).coerceAtMost(30)
    // Long overflow를 방지한다. baseMs shl shift가 Long.MAX_VALUE를 넘으면 즉시 maxMs로 제한한다.
    val cappedMs = if (shift > 0 && baseMs > (Long.MAX_VALUE ushr shift)) {
        maxMs
    } else {
        (baseMs shl shift).coerceAtMost(maxMs)
    }
    return Random.Default.nextLong(0L, cappedMs + 1L).milliseconds
}
