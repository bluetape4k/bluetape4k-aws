package io.bluetape4k.aws.spring

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

/**
 * bluetape4k-aws Spring Boot 자동 설정.
 * AWS SDK v2 공통 빈 등록.
 */
@AutoConfiguration
class AwsAutoConfiguration {

    companion object: KLogging()

    @Bean
    @ConditionalOnMissingBean
    fun defaultAwsCredentialsProvider(): AwsCredentialsProvider {
        log.debug("Registering DefaultCredentialsProvider")
        return DefaultCredentialsProvider.builder().build()
    }
}
