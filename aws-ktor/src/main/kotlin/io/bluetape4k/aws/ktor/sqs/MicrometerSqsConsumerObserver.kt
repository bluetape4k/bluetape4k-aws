package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.aws.ktor.observability.KtorMicrometerSupport
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.time.Duration

/**
 * Bridges [SqsConsumerObservation] events to Micrometer timers.
 *
 * Install through [micrometer] to keep Micrometer opt-in for Ktor users.
 */
class MicrometerSqsConsumerObserver(
    private val meterRegistry: MeterRegistry,
    private val meterName: String = DEFAULT_METER_NAME,
): SqsConsumerObserver {

    override fun observe(observation: SqsConsumerObservation) {
        KtorMicrometerSupport.record(
            meterRegistry = meterRegistry,
            meterName = meterName,
            tags = tags(observation),
            duration = observation.duration ?: Duration.ZERO,
        )
    }

    private fun tags(observation: SqsConsumerObservation): Tags =
        KtorMicrometerSupport.tags(
            service = "sqs",
            operation = observation.operation,
            outcome = observation.outcome,
            exception = observation.tags["exception"] ?: "none",
            extras = listOf(KtorMicrometerSupport.queueNameTag(observation.queueUrl)),
        )

    companion object {
        const val DEFAULT_METER_NAME: String = "bluetape4k.aws.ktor.sqs.operation"
    }
}

/**
 * Adds a Micrometer observer to this SQS consumer plugin configuration.
 */
fun SqsConsumerPluginConfig.micrometer(
    meterRegistry: MeterRegistry,
    meterName: String = MicrometerSqsConsumerObserver.DEFAULT_METER_NAME,
) {
    observer(MicrometerSqsConsumerObserver(meterRegistry, meterName))
}
