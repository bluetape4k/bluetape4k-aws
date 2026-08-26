package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.shouldBeEqualTo
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration

class AwsModulithMetricsTest {

    @Test
    fun `records only bounded tags and no-ops without a registry`() {
        AwsModulithMetrics().record(
            service = AwsModulithMetricService.SQS,
            phase = AwsModulithFailurePhase.CLAIM,
            outcome = AwsModulithMetricOutcome.FAILURE,
            code = AwsModulithDiagnosticCode.CLAIM,
        )

        val registry = SimpleMeterRegistry()
        val metrics = AwsModulithMetrics(registry)
        metrics.record(
            service = AwsModulithMetricService.SQS,
            phase = AwsModulithFailurePhase.CLAIM,
            outcome = AwsModulithMetricOutcome.FAILURE,
            code = AwsModulithDiagnosticCode.CLAIM,
        )
        metrics.recordLatency(
            service = AwsModulithMetricService.SNS,
            phase = AwsModulithFailurePhase.PUBLISH,
            outcome = AwsModulithMetricOutcome.SUCCESS,
            code = AwsModulithDiagnosticCode.AWS_PUBLISH,
            duration = Duration.ofMillis(12),
        )
        metrics.changeInFlight(AwsModulithMetricService.SQS, 1)
        metrics.changeInFlight(AwsModulithMetricService.SQS, -1)

        registry.meters.size shouldBeEqualTo 3
        registry.meters.forEach { meter ->
            meter.id.tags.map { it.key }.sorted() shouldBeEqualTo listOf("code", "outcome", "phase", "service")
            meter.id.tags.none { tag -> SENSITIVE.any(tag.value::contains) } shouldBeEqualTo true
        }
        registry.get("bluetape4k.aws.modulith.events.latency").timer().count() shouldBeEqualTo 1L
        registry.get("bluetape4k.aws.modulith.events.inflight").gauge().value() shouldBeEqualTo 0.0
    }

    private companion object {
        val SENSITIVE = listOf("payload", "event-id", "arn:", "message-id")
    }
}
