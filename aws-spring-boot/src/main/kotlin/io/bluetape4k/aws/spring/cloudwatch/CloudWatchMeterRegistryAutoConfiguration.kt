package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient

/**
 * 선택적으로 native Micrometer CloudWatch registry를 등록하는 Spring Boot 4 자동 구성입니다.
 *
 * 기존 사용자 [MeterRegistry]가 있으면 해당 registry와 수동 CloudWatch helper의 소유권을
 * 존중하고 이 자동 구성은 back-off합니다.
 */
@AutoConfiguration(
    after = [AwsAutoConfiguration::class, CloudWatchAutoConfiguration::class],
    afterName = ["org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration"],
    beforeName = ["org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"],
)
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient",
        "io.micrometer.cloudwatch2.CloudWatchMeterRegistry",
    ],
)
@ConditionalOnProperty(
    prefix = CLOUDWATCH_PROPERTIES_PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@ConditionalOnProperty(
    prefix = "$CLOUDWATCH_PROPERTIES_PREFIX.micrometer.registry",
    name = ["enabled"],
    havingValue = "true",
)
@ConditionalOnBean(CloudWatchAsyncClient::class)
@ConditionalOnMissingBean(MeterRegistry::class)
class CloudWatchMeterRegistryAutoConfiguration {

    @Bean(destroyMethod = "close")
    fun cloudWatchMeterRegistry(
        cloudWatchAsyncClient: CloudWatchAsyncClient,
        properties: CloudWatchProperties,
        clock: ObjectProvider<Clock>,
    ): CloudWatchMeterRegistry =
        CloudWatchMeterRegistryConfiguration.create(
            cloudWatchAsyncClient = cloudWatchAsyncClient,
            properties = properties,
            clock = clock.getIfAvailable { Clock.SYSTEM },
        )
}
