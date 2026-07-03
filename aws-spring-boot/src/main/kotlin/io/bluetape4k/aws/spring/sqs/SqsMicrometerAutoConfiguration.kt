package io.bluetape4k.aws.spring.sqs

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * Auto-configures Micrometer instrumentation for Spring SQS operations.
 *
 * The observed [SqsOperations] bean is primary, while the underlying
 * [SqsCoroutinesTemplate] remains available for applications that inject the
 * concrete template type.
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
    fun micrometerSqsOperations(
        sqsCoroutinesTemplate: SqsCoroutinesTemplate,
        meterRegistry: MeterRegistry,
    ): MicrometerSqsOperations =
        MicrometerSqsOperations(sqsCoroutinesTemplate, meterRegistry)

    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(MicrometerSqsListenerInterceptor::class)
    fun micrometerSqsListenerInterceptor(meterRegistry: MeterRegistry): MicrometerSqsListenerInterceptor =
        MicrometerSqsListenerInterceptor(meterRegistry)
}
