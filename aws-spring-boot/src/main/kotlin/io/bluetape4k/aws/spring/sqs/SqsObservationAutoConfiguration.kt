package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.core.type.AnnotatedTypeMetadata

/** SQS 관찰 runtime이 실제로 활성화됐음을 나타내는 내부 marker입니다. */
internal class SqsObservationActivation

/** 실제 관찰 처리 경로가 준비된 경우에만 SQS observation runtime을 자동 구성합니다. */
@AutoConfiguration(
    afterName = ["org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration"],
    before = [SqsAutoConfiguration::class],
)
@ConditionalOnAwsEnabled
@ConditionalOnProperty(prefix = "bluetape4k.aws.sqs", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "bluetape4k.aws.sqs.observation", name = ["enabled"], havingValue = "true")
@ConditionalOnClass(
    name = [
        "io.micrometer.observation.ObservationRegistry",
        "io.micrometer.context.ContextSnapshot",
    ],
)
@EnableConfigurationProperties(SqsObservationProperties::class)
class SqsObservationAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @Conditional(SqsObservationPrerequisitesCondition::class)
    internal class ActivatedObservationConfiguration {

        @Bean
        fun sqsObservationActivation(): SqsObservationActivation = SqsObservationActivation()

        @Bean
        fun sqsObservationRuntime(
            registry: ObservationRegistry,
            customizers: ObjectProvider<SqsObservationContextCustomizer>,
            conventions: ObjectProvider<SqsObservationConvention>,
            factories: ObjectProvider<SqsObservationFactory>,
        ): SqsObservationRuntime {
            val resolvedConventions = resolveSqsObservationConventions(conventions.orderedStream().toList())
            val factory = factories.getIfAvailable { defaultSqsObservationFactory(resolvedConventions) }
            return SqsObservationRuntime(
                registry = registry,
                customizers = customizers.orderedStream().toList(),
                factory = factory,
            )
        }
    }
}

internal class SqsObservationPrerequisitesCondition : SpringBootCondition(), ConfigurationCondition {

    override fun getConfigurationPhase(): ConfigurationCondition.ConfigurationPhase =
        ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN

    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): ConditionOutcome {
        val beanFactory = context.beanFactory
        val failureReason = if (beanFactory == null) {
            "bean-factory-missing"
        } else {
            findFailureReason(beanFactory)
        }
        val matchReason = if (beanFactory?.hasUserSqsObservationFactory() == true) {
            "user-factory"
        } else {
            "supporting-handler"
        }
        return failureReason
            ?.let(::noMatch)
            ?: ConditionOutcome.match("$REASON_CODE $matchReason")
    }

    private fun findFailureReason(beanFactory: ConfigurableListableBeanFactory): String? {
        val registry = beanFactory.getBeanProvider(ObservationRegistry::class.java).getIfAvailable()
        return when {
            registry == null -> "registry-missing"
            registry === ObservationRegistry.NOOP -> "registry-noop"
            !hasSupportingHandler(beanFactory) -> "handler-missing"
            else -> null
        }
    }

    private fun hasSupportingHandler(beanFactory: ConfigurableListableBeanFactory): Boolean {
        val probe = SqsObservationContext(
            SqsObservationMetadata(
                listenerId = "auto-configuration",
                queueName = "probe",
                stage = SqsObservationStage.PROCESS,
                batch = false,
                initialAttempt = 1,
            ),
        )
        return beanFactory
            .getBeanProvider(ObservationHandler::class.java)
            .orderedStream()
            .anyMatch { handler -> handler.supportsContext(probe) }
    }

    private fun noMatch(reason: String): ConditionOutcome =
        ConditionOutcome.noMatch("$REASON_CODE $reason")

    private fun ConfigurableListableBeanFactory.hasUserSqsObservationFactory(): Boolean =
        getBeanNamesForType(SqsObservationFactory::class.java, false, false).isNotEmpty()

    private companion object {
        const val REASON_CODE: String = "BT4K-SQS-OBS-101"
    }
}
