package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.aws.ktor.observability.KtorMicrometerSupport
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.time.Duration

/**
 * [SqsConsumerObservation] 이벤트를 Micrometer timer로 전달합니다.
 *
 * Ktor 사용자가 Micrometer 의존성을 명시적으로 선택할 수 있도록 [micrometer] 확장 함수로 등록합니다.
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
            service = KtorMicrometerSupport.SERVICE_SQS,
            operation = observation.operation,
            outcome = observation.outcome,
            exception = observation.tags[KtorSqsObservationTags.EXCEPTION] ?: KtorMicrometerSupport.EXCEPTION_NONE,
            extras = listOf(KtorMicrometerSupport.queueNameTag(observation.queueUrl)),
        )

    companion object {
        const val DEFAULT_METER_NAME: String = "bluetape4k.aws.ktor.sqs.operation"
    }
}

/**
 * 이 SQS consumer 플러그인 설정에 Micrometer observer를 추가합니다.
 */
fun SqsConsumerPluginConfig.micrometer(
    meterRegistry: MeterRegistry,
    meterName: String = MicrometerSqsConsumerObserver.DEFAULT_METER_NAME,
) {
    observer(MicrometerSqsConsumerObserver(meterRegistry, meterName))
}
