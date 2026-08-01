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
 * Spring S3 작업의 Micrometer 계측을 자동 구성합니다.
 *
 * 관찰되는 [S3Operations] Bean이 primary이며, 구체적인 템플릿 타입을 주입하는 애플리케이션에서는
 * 하위 [S3CoroutinesTemplate]도 계속 사용할 수 있습니다.
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
