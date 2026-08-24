package io.bluetape4k.aws.spring

import io.bluetape4k.aws.spring.connection.AwsServiceConnectionCredentialsResolver
import io.bluetape4k.aws.spring.connection.AwsServiceConnectionDetails
import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.ObjectProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider

/**
 * 공유 AWS SDK v2 지원을 위한 Spring Boot 자동 구성입니다.
 */
@AutoConfiguration
@ConditionalOnAwsEnabled
@ConditionalOnProperty(prefix = "bluetape4k.aws", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AwsProperties::class)
class AwsAutoConfiguration {

    companion object: KLogging()

    @Bean
    @ConditionalOnClass(name = ["software.amazon.awssdk.services.sts.StsClient"])
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.credentials.web-identity",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(AwsCredentialsProvider::class)
    fun webIdentityAwsCredentialsProvider(properties: AwsProperties): AwsCredentialsProvider {
        log.debug("Registering WebIdentityTokenFileCredentialsProvider")
        val webIdentity = properties.credentials.webIdentity

        return WebIdentityTokenFileCredentialsProvider.builder()
            .apply {
                webIdentity.roleArn?.takeIf { it.isNotBlank() }?.let { roleArn(it) }
                webIdentity.roleSessionName?.takeIf { it.isNotBlank() }?.let { roleSessionName(it) }
                webIdentity.tokenFile?.let { webIdentityTokenFile(it) }
            }
            .build()
    }

    @Bean
    @ConditionalOnMissingBean
    fun defaultAwsCredentialsProvider(
        connectionDetails: ObjectProvider<AwsServiceConnectionDetails>,
    ): AwsCredentialsProvider {
        log.debug("Registering DefaultCredentialsProvider")
        return AwsServiceConnectionCredentialsResolver.resolve(connectionDetails)
    }
}
