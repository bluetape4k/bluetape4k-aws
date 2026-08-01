package io.bluetape4k.aws.spring.ses

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsAsyncClientCustomizer
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.applyGlobalCustomizers
import io.bluetape4k.aws.spring.applyServiceCustomizers
import io.bluetape4k.aws.spring.resolveClientDefaults
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient
import software.amazon.awssdk.services.sesv2.SesV2AsyncClientBuilder
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * AWS SES용 Spring Boot 4 자동 구성입니다.
 *
 * ## 계약
 *
 * 런타임 클래스패스에 SES v2 SDK가 있고 `bluetape4k.aws.ses.enabled`를 비활성화하지 않았으면
 * [SesV2AsyncClient]와 [SesOperations]를 등록합니다.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.services.sesv2.SesV2AsyncClient",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.ses", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SesProperties::class)
class SesAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun sesV2AsyncClient(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: SesProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
        globalCustomizers: ObjectProvider<AwsAsyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<SesV2AsyncClientBuilder>>,
    ): SesV2AsyncClient =
        SesV2AsyncClient.builder()
            .credentialsProvider(resolveCredentialsProvider(credentialsProvider))
            .applyAwsDefaults(
                resolveAwsProperties(awsProperties).resolveClientDefaults(properties.region, properties.endpointOverride)
            )
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .also { it.applyGlobalCustomizers("ses", globalCustomizers) }
            .applyServiceCustomizers(serviceCustomizers)
            .build()

    @Bean
    @ConditionalOnMissingBean(SesOperations::class)
    fun sesCoroutinesMailSender(
        sesV2AsyncClient: SesV2AsyncClient,
        properties: SesProperties,
    ): SesCoroutinesMailSender =
        SesCoroutinesMailSender(sesV2AsyncClient, properties)

    private fun resolveCredentialsProvider(
        provider: ObjectProvider<AwsCredentialsProvider>,
    ): AwsCredentialsProvider =
        provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }

    private fun resolveAwsProperties(provider: ObjectProvider<AwsProperties>): AwsProperties =
        provider.getIfAvailable { AwsProperties() }
}
