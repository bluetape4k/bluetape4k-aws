package io.bluetape4k.aws.spring.sqs

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.springframework.core.annotation.AnnotationAwareOrderComparator

/**
 * observation 생성 전에 정제된 context를 보완하는 사용자 확장점입니다.
 *
 * 여러 bean은 Spring `Ordered` 또는 `@Order` 순서로 정확히 한 번 실행됩니다. 원본 메시지나
 * receipt handle은 제공되지 않으며, 사용자가 임의 데이터를 추가할 때의 보안과 cardinality는
 * 사용자 책임입니다.
 */
fun interface SqsObservationContextCustomizer {
    fun customize(context: SqsObservationContext)
}

/**
 * supplied [registry]와 동일한 [context]로 시작되지 않은 observation을 만드는 확장점입니다.
 *
 * [Observation.NOOP] 반환은 허용합니다. 다른 context 또는 registry를 사용하면 runtime이
 * fail fast합니다. Micrometer public API는 started 상태 조회를 제공하지 않으므로 이미 시작한
 * observation 반환은 지원하지 않습니다. `start`, `error`, `stop` lifecycle은 runtime만 소유합니다.
 */
fun interface SqsObservationFactory {
    fun createNotStarted(
        context: SqsObservationContext,
        registry: ObservationRegistry,
    ): Observation
}

internal fun defaultSqsObservationFactory(
    conventions: Map<SqsObservationStage, SqsObservationConvention>,
): SqsObservationFactory = DefaultSqsObservationFactory(conventions)

internal fun prepareSqsObservation(
    context: SqsObservationContext,
    registry: ObservationRegistry,
    customizers: List<SqsObservationContextCustomizer>,
    factory: SqsObservationFactory,
): Observation {
    customizers.forEach { it.customize(context) }

    val observation = factory.createNotStarted(context, registry)
    if (observation !== Observation.NOOP) {
        check(observation.context === context) {
            "SqsObservationFactory must use the supplied context instance."
        }
        check(observation.observationRegistry === registry) {
            "SqsObservationFactory must use the supplied registry instance."
        }
    }
    return observation
}

internal fun orderedSqsObservationCustomizers(
    customizers: List<SqsObservationContextCustomizer>,
): List<SqsObservationContextCustomizer> = customizers
    .toMutableList()
    .also(AnnotationAwareOrderComparator::sort)
    .toList()

internal fun requireSqsObservationRegistryBinding(
    registry: ObservationRegistry,
    observation: Observation,
) {
    check(observation.observationRegistry === registry && registry.currentObservation === observation) {
        "SqsObservationFactory must bind the observation to the supplied registry."
    }
}

internal class SqsObservationTelemetryException(
    val failureStage: String,
) : RuntimeException(null, null, false, false)

private class DefaultSqsObservationFactory(
    private val conventions: Map<SqsObservationStage, SqsObservationConvention>,
) : SqsObservationFactory {
    override fun createNotStarted(
        context: SqsObservationContext,
        registry: ObservationRegistry,
    ): Observation {
        val convention = conventions.getValue(context.metadata.stage)
        return Observation.createNotStarted(convention, { context }, registry)
    }
}
