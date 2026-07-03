package io.bluetape4k.aws.spring.s3

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
 * Auto-configures Micrometer instrumentation for Spring S3 operations.
 *
 * The observed [S3Operations] bean is primary, while the underlying
 * [S3CoroutinesTemplate] remains available for applications that inject the
 * concrete template type.
 */
@AutoConfiguration(after = [S3AutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(MeterRegistry::class)
@ConditionalOnProperty(prefix = "bluetape4k.aws.s3", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class S3MicrometerAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnBean(value = [MeterRegistry::class, S3CoroutinesTemplate::class])
    @ConditionalOnMissingBean(MicrometerS3Operations::class)
    fun micrometerS3Operations(
        s3CoroutinesTemplate: S3CoroutinesTemplate,
        meterRegistry: MeterRegistry,
    ): MicrometerS3Operations =
        MicrometerS3Operations(s3CoroutinesTemplate, meterRegistry)
}
