package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * AWS SDK SNS message manager 기반 HTTP 서명 검증 자동 구성입니다.
 *
 * SNS SDK와 message manager가 runtime classpath에 있고 SNS 및 verification이 활성화된
 * 경우에만 verifier bean을 등록합니다.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class, SnsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(name = ["software.amazon.awssdk.messagemanager.sns.SnsMessageManager"])
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.sns",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.sns.verification",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(SnsProperties::class)
class SnsHttpMessageVerificationAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(SnsHttpMessageVerifier::class)
    fun snsHttpMessageVerifier(properties: SnsProperties): SnsHttpMessageVerifier =
        SnsHttpMessageVerifier.forRegion(properties.region)
}
