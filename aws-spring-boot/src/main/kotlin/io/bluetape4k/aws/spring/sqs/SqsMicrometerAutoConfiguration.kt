package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Spring SQS 작업의 Micrometer 계측을 자동 구성합니다.
 *
 * 관찰되는 [SqsOperations] Bean이 primary이며, 구체적인 템플릿 타입을 주입하는 애플리케이션에서는
 * 하위 [SqsCoroutinesTemplate]도 계속 사용할 수 있습니다.
 */
@AutoConfiguration(after = [SqsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(MeterRegistry::class)
@ConditionalOnProperty(prefix = "bluetape4k.aws.sqs", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SqsMicrometerAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnBean(value = [MeterRegistry::class, SqsCoroutinesTemplate::class])
    @ConditionalOnMissingBean(MicrometerSqsOperations::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.sqs.extended",
        name = ["enabled"],
        havingValue = "false",
        matchIfMissing = true,
    )
    fun micrometerSqsOperations(
        sqsCoroutinesTemplate: SqsCoroutinesTemplate,
        meterRegistry: MeterRegistry,
    ): MicrometerSqsOperations =
        MicrometerSqsOperations(sqsCoroutinesTemplate, meterRegistry)

    @Bean
    @Primary
    @ConditionalOnBean(value = [MeterRegistry::class, SqsCoroutinesTemplate::class])
    @ConditionalOnMissingBean(MicrometerFullRequestSqsOperations::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.sqs.extended",
        name = ["enabled"],
        havingValue = "true",
    )
    fun micrometerFullRequestSqsOperations(
        sqsCoroutinesTemplate: SqsCoroutinesTemplate,
        meterRegistry: MeterRegistry,
    ): MicrometerFullRequestSqsOperations =
        MicrometerFullRequestSqsOperations(sqsCoroutinesTemplate, meterRegistry)

    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(
        value = [
            MicrometerSqsListenerInterceptor::class,
            SqsObservationActivation::class,
        ],
    )
    fun micrometerSqsListenerInterceptor(meterRegistry: MeterRegistry): MicrometerSqsListenerInterceptor =
        MicrometerSqsListenerInterceptor(meterRegistry)
}
