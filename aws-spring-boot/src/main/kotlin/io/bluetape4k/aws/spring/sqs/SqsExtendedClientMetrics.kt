package io.bluetape4k.aws.spring.sqs

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry

/**
 * SQS Extended Client의 bounded vocabulary metric을 기록합니다.
 *
 * queue URL, bucket, object key, diagnostic code 같은 요청별 값은 tag로 사용하지
 * 않아 series cardinality가 입력 데이터에 따라 증가하지 않도록 합니다.
 */
class SqsExtendedClientMetrics(
    private val meterRegistry: MeterRegistry,
) {

    fun recordOffloadInline() = offloadCounter("inline").increment()

    fun recordOffloadSuccess() = offloadCounter("offloaded").increment()

    fun recordOffloadRejected() = offloadCounter("rejected").increment()

    fun recordOrphanSqsSend() = orphanCounter("sqs-send").increment()

    fun recordOrphanCancelled() = orphanCounter("cancelled").increment()

    fun recordPayloadReadFailure(failureKind: PayloadReadFailureKind) =
        payloadReadCounter(failureKind.name).increment()

    fun recordCleanupFailure(failureKind: CleanupFailureKind) =
        cleanupCounter(failureKind.name).increment()

    private fun offloadCounter(outcome: String): Counter =
        Counter.builder(OFFLOAD_METRIC)
            .tag("outcome", outcome)
            .register(meterRegistry)

    private fun orphanCounter(reason: String): Counter =
        Counter.builder(ORPHAN_METRIC)
            .tag("reason", reason)
            .register(meterRegistry)

    private fun payloadReadCounter(failureKind: String): Counter =
        Counter.builder(PAYLOAD_READ_FAILURE_METRIC)
            .tag("failureKind", failureKind)
            .register(meterRegistry)

    private fun cleanupCounter(failureKind: String): Counter =
        Counter.builder(CLEANUP_FAILURE_METRIC)
            .tag("failureKind", failureKind)
            .register(meterRegistry)

    enum class PayloadReadFailureKind {
        S3_READ,
        POINTER_FORMAT,
        CONFIGURATION,
    }

    enum class CleanupFailureKind {
        S3_DELETE,
        CONFIGURATION,
    }

    companion object {
        const val OFFLOAD_METRIC = "bluetape4k.aws.sqs.extended.offload.total"
        const val ORPHAN_METRIC = "bluetape4k.aws.sqs.extended.orphan.total"
        const val PAYLOAD_READ_FAILURE_METRIC = "bluetape4k.aws.sqs.extended.payload-read.failure"
        const val CLEANUP_FAILURE_METRIC = "bluetape4k.aws.sqs.extended.cleanup.failure"
    }
}
