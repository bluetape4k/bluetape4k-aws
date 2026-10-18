package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.support.requireInRange
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

internal const val SQS_BATCH_PROPERTIES_PREFIX = "bluetape4k.aws.sqs.batch"
private const val DEFAULT_FLUSH_INTERVAL_MILLIS = 200L
private const val DEFAULT_MAX_ENTRIES_PER_CALL = 1_000
private const val DEFAULT_MAX_IN_FLIGHT_ENTRIES = 100
private const val DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 5L
private const val MAX_CONFIGURED_ENTRIES = 10_000
private const val MAX_SCHEDULER_THREADS = 16

/**
 * SQS 자동 배치 실행 설정입니다.
 *
 * [enabled]가 `false`이면 동일한 공개 API가 단건 direct transport를 사용합니다.
 */
@ConfigurationProperties(prefix = SQS_BATCH_PROPERTIES_PREFIX)
data class SqsBatchProperties(
    val enabled: Boolean = false,
    val maxBatchSize: Int = MAX_SQS_BATCH_SIZE,
    val flushInterval: Duration = DEFAULT_FLUSH_INTERVAL_MILLIS.millis(),
    val maxEntriesPerCall: Int = DEFAULT_MAX_ENTRIES_PER_CALL,
    val maxInFlightEntries: Int = DEFAULT_MAX_IN_FLIGHT_ENTRIES,
    val schedulerThreads: Int = 1,
    val shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT_SECONDS.seconds(),
): Serializable {

    init {
        maxBatchSize.requireBatchPropertyInRange(1, MAX_SQS_BATCH_SIZE, "max-batch-size")
        flushInterval.requireBatchPropertyInRange(
            1.millis(),
            1.minutes(),
            "flush-interval",
        )
        maxEntriesPerCall.requireBatchPropertyInRange(1, MAX_CONFIGURED_ENTRIES, "max-entries-per-call")
        maxInFlightEntries.requireBatchPropertyInRange(1, MAX_CONFIGURED_ENTRIES, "max-in-flight-entries")
        schedulerThreads.requireBatchPropertyInRange(1, MAX_SCHEDULER_THREADS, "scheduler-threads")
        shutdownTimeout.requireBatchPropertyInRange(
            1.millis(),
            1.minutes(),
            "shutdown-timeout",
        )
        require(!enabled || maxInFlightEntries >= maxBatchSize) {
            "$SQS_BATCH_PROPERTIES_PREFIX.max-in-flight-entries must cover max-batch-size"
        }
        require(!enabled || shutdownTimeout >= flushInterval) {
            "$SQS_BATCH_PROPERTIES_PREFIX.shutdown-timeout must cover flush-interval"
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private fun <T: Comparable<T>> T.requireBatchPropertyInRange(
    minimum: T,
    maximum: T,
    propertyName: String,
): T =
    requireInRange(minimum, maximum) {
        "$SQS_BATCH_PROPERTIES_PREFIX.$propertyName must be in the supported range"
    }
