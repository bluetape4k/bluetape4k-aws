package io.bluetape4k.aws.spring.modulith

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Modulith adapter가 metric tag로 허용하는 bounded service 값입니다. */
internal enum class AwsModulithMetricService {
    SNS,
    SQS,
    NONE,
}

/** Modulith adapter가 metric tag로 허용하는 bounded outcome 값입니다. */
internal enum class AwsModulithMetricOutcome {
    SUCCESS,
    FAILURE,
    REJECTED,
    DUPLICATE,
    IN_PROGRESS,
    TAKEOVER,
    ACTIVE,
    COMPLETED,
}

/** Micrometer가 없을 때 no-op으로 동작하는 low-cardinality metric 경계입니다. */
internal class AwsModulithMetrics(
    private val registry: MeterRegistry? = null,
) {
    private val inFlight = ConcurrentHashMap<AwsModulithMetricService, AtomicInteger>()

    fun record(
        service: AwsModulithMetricService,
        phase: AwsModulithFailurePhase,
        outcome: AwsModulithMetricOutcome,
        code: AwsModulithDiagnosticCode,
    ) {
        val targetRegistry = registry ?: return
        Counter.builder(METER_NAME)
            .tags(tags(service, phase, outcome, code))
            .register(targetRegistry)
            .increment()
    }

    fun recordLatency(
        service: AwsModulithMetricService,
        phase: AwsModulithFailurePhase,
        outcome: AwsModulithMetricOutcome,
        code: AwsModulithDiagnosticCode,
        duration: Duration,
    ) {
        val targetRegistry = registry ?: return
        Timer.builder(LATENCY_METER_NAME)
            .tags(tags(service, phase, outcome, code))
            .register(targetRegistry)
            .record(duration)
    }

    fun changeInFlight(service: AwsModulithMetricService, delta: Int) {
        val targetRegistry = registry ?: return
        val value = inFlight.computeIfAbsent(service) {
            AtomicInteger().also { active ->
                Gauge.builder(IN_FLIGHT_METER_NAME, active) { it.get().toDouble() }
                    .tags(
                        tags(
                            service,
                            AwsModulithFailurePhase.DISPATCH,
                            AwsModulithMetricOutcome.ACTIVE,
                            AwsModulithDiagnosticCode.DISPATCH_ACK,
                        )
                    )
                    .register(targetRegistry)
            }
        }
        value.updateAndGet { current -> (current + delta).coerceAtLeast(0) }
    }

    private fun tags(
        service: AwsModulithMetricService,
        phase: AwsModulithFailurePhase,
        outcome: AwsModulithMetricOutcome,
        code: AwsModulithDiagnosticCode,
    ): Tags = Tags.of(
        "service", service.name.lowercase(),
        "phase", phase.name.lowercase(),
        "outcome", outcome.name.lowercase(),
        "code", code.value,
    )

    companion object {
        private const val METER_NAME = "bluetape4k.aws.modulith.events"
        private const val LATENCY_METER_NAME = "$METER_NAME.latency"
        private const val IN_FLIGHT_METER_NAME = "$METER_NAME.inflight"
    }
}
