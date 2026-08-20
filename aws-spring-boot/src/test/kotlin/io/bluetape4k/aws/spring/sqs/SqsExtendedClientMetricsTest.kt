package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

class SqsExtendedClientMetricsTest {

    @Test
    fun `metrics keep the four names and bounded tag vocabulary`() {
        val registry = SimpleMeterRegistry()
        val metrics = SqsExtendedClientMetrics(registry)

        metrics.recordOffloadInline()
        metrics.recordOffloadSuccess()
        metrics.recordOffloadRejected()
        metrics.recordOrphanSqsSend()
        metrics.recordOrphanCancelled()
        metrics.recordPayloadReadFailure(SqsExtendedClientMetrics.PayloadReadFailureKind.S3_READ)
        metrics.recordPayloadReadFailure(SqsExtendedClientMetrics.PayloadReadFailureKind.POINTER_FORMAT)
        metrics.recordPayloadReadFailure(SqsExtendedClientMetrics.PayloadReadFailureKind.CONFIGURATION)
        metrics.recordCleanupFailure(SqsExtendedClientMetrics.CleanupFailureKind.S3_DELETE)
        metrics.recordCleanupFailure(SqsExtendedClientMetrics.CleanupFailureKind.CONFIGURATION)

        registry.meters.map { it.id.name }.distinct().sorted() shouldBeEqualTo listOf(
            SqsExtendedClientMetrics.CLEANUP_FAILURE_METRIC,
            SqsExtendedClientMetrics.OFFLOAD_METRIC,
            SqsExtendedClientMetrics.ORPHAN_METRIC,
            SqsExtendedClientMetrics.PAYLOAD_READ_FAILURE_METRIC,
        )
        registry.find(SqsExtendedClientMetrics.OFFLOAD_METRIC)
            .tag("outcome", "inline")
            .counter()?.count() shouldBeEqualTo 1.0
        registry.find(SqsExtendedClientMetrics.OFFLOAD_METRIC)
            .tag("outcome", "offloaded")
            .counter()?.count() shouldBeEqualTo 1.0
        registry.find(SqsExtendedClientMetrics.OFFLOAD_METRIC)
            .tag("outcome", "rejected")
            .counter()?.count() shouldBeEqualTo 1.0
        registry.find(SqsExtendedClientMetrics.ORPHAN_METRIC)
            .tag("reason", "sqs-send")
            .counter()?.count() shouldBeEqualTo 1.0
        registry.find(SqsExtendedClientMetrics.ORPHAN_METRIC)
            .tag("reason", "cancelled")
            .counter()?.count() shouldBeEqualTo 1.0

        val forbidden = listOf(
            "queueUrl-${Base58.randomString(16)}",
            "bucket-${Base58.randomString(16)}",
            "key-${Base58.randomString(16)}",
            "diagnosticCode",
        )
        registry.meters.flatMap { meter -> meter.id.tags.map { it.key } } shouldNotContain "queueUrl"
        registry.meters.flatMap { meter -> meter.id.tags.map { it.key } } shouldNotContain "bucket"
        registry.meters.flatMap { meter -> meter.id.tags.map { it.key } } shouldNotContain "key"
        registry.meters.flatMap { meter -> meter.id.tags.map { it.key } } shouldNotContain "diagnosticCode"
        forbidden.forEach { value -> registry.meters.map { it.id.toString() } shouldNotContain value }
    }
}
