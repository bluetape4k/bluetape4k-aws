package io.bluetape4k.aws.kotlin.kinesis

import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [KinesisClient.recordFlow] 조정 옵션입니다.
 *
 * ## 기본값
 * | 옵션 | 기본값 | 설명 |
 * |---|---|---|
 * | batchLimit | 100 | Kinesis 최댓값은 10 000 |
 * | pollInterval | 200 ms | Kinesis 할당량에 따른 최소 권장 폴링 간격 |
 * | emptyBackoff | 1 s | GetRecords 응답에 레코드가 없을 때의 지연 |
 * | maxIteratorRetries | 3 | 최대 [ExpiredIteratorException] 복구 시도 횟수 |
 * | initialThrottleBackoff | 500 ms | 제한 재시도의 지수 백오프 시작값 |
 * | maxThrottleBackoff | 30 s | 제한 재시도 사이의 단일 최대 지연 |
 * | maxThrottleRetries | 5 | 재시도 가능한 [KinesisException]의 최대 시도 횟수 |
 *
 * @param batchLimit [GetRecords] 호출당 최대 레코드 수. 1..[MAX_KINESIS_BATCH_LIMIT] 범위여야 합니다.
 * @param pollInterval 레코드를 반환한 성공적인 [GetRecords] 호출 사이의 지연. Kinesis의 샤드당 초당 5회
 *   할당량을 지키려면 [MIN_POLL_INTERVAL] 이상이어야 합니다.
 * @param emptyBackoff [GetRecords] 응답의 레코드가 0개일 때의 지연
 * @param maxIteratorRetries 샤드 반복자를 다시 가져와 [ExpiredIteratorException]에서 복구할 최대 횟수.
 *   이 한도를 소진하면 예외를 던집니다.
 * @param initialThrottleBackoff 제한 재시도의 지수 지터에 사용할 시작 백오프 시간
 * @param maxThrottleBackoff 단일 제한 재시도 지연의 상한(지터 적용 전)
 * @param maxThrottleRetries 재시도 가능한 [KinesisException]을 Flow가 재시도할 최대 횟수.
 *   이 한도를 소진하면 예외를 던집니다.
 */
data class KinesisRecordFlowOptions(
    val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    val emptyBackoff: Duration = DEFAULT_EMPTY_BACKOFF,
    val maxIteratorRetries: Int = DEFAULT_MAX_ITERATOR_RETRIES,
    val initialThrottleBackoff: Duration = DEFAULT_INITIAL_THROTTLE_BACKOFF,
    val maxThrottleBackoff: Duration = DEFAULT_MAX_THROTTLE_BACKOFF,
    val maxThrottleRetries: Int = DEFAULT_MAX_THROTTLE_RETRIES,
) : Serializable {

    init {
        require(batchLimit in 1..MAX_KINESIS_BATCH_LIMIT) {
            "batchLimit must be in 1..$MAX_KINESIS_BATCH_LIMIT, but was $batchLimit"
        }
        require(pollInterval >= MIN_POLL_INTERVAL) {
            "pollInterval must be ≥ $MIN_POLL_INTERVAL, but was $pollInterval"
        }
        require(emptyBackoff.isPositive()) {
            "emptyBackoff must be positive, but was $emptyBackoff"
        }
        require(maxIteratorRetries >= 0) {
            "maxIteratorRetries must be ≥ 0, but was $maxIteratorRetries"
        }
        require(initialThrottleBackoff.isPositive()) {
            "initialThrottleBackoff must be positive, but was $initialThrottleBackoff"
        }
        require(maxThrottleBackoff >= initialThrottleBackoff) {
            "maxThrottleBackoff ($maxThrottleBackoff) must be ≥ initialThrottleBackoff ($initialThrottleBackoff)"
        }
        require(maxThrottleRetries >= 0) {
            "maxThrottleRetries must be ≥ 0, but was $maxThrottleRetries"
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

/** 단일 GetRecords 호출에 적용되는 Kinesis API 상한입니다. */
        const val MAX_KINESIS_BATCH_LIMIT: Int = 10_000

/** 샤드당 초당 5회 호출 할당량을 넘지 않기 위한 최소 안전 폴링 간격입니다. */
        val MIN_POLL_INTERVAL: Duration = 200.milliseconds

        const val DEFAULT_BATCH_LIMIT: Int = 100
        val DEFAULT_POLL_INTERVAL: Duration = 200.milliseconds
        val DEFAULT_EMPTY_BACKOFF: Duration = 1.seconds
        const val DEFAULT_MAX_ITERATOR_RETRIES: Int = 3
        val DEFAULT_INITIAL_THROTTLE_BACKOFF: Duration = 500.milliseconds
        val DEFAULT_MAX_THROTTLE_BACKOFF: Duration = 30.seconds
        const val DEFAULT_MAX_THROTTLE_RETRIES: Int = 5
    }
}
