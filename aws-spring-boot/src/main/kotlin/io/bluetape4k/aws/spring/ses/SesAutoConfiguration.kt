package io.bluetape4k.aws.spring.ses

import io.bluetape4k.aws.spring.AwsAutoConfiguration
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
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient

/**
 * Spring Boot 4 auto-configuration for AWS SES.
 *
 * ## Contract
 *
 * Registers an [SesV2AsyncClient] and [SesOperations] when the SES v2 SDK is
 * on the runtime classpath and `bluetape4k.aws.ses.enabled` is not disabled.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
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
        properties: SesProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
    ): SesV2AsyncClient =
        SesV2AsyncClient.builder()
            .credentialsProvider(resolveCredentialsProvider(credentialsProvider))
            .apply {
                properties.region?.let { region(Region.of(it)) }
                properties.endpointOverride?.let { endpointOverride(it) }
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
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
}
