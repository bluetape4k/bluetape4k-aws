package io.bluetape4k.aws.spring.sns

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
import software.amazon.awssdk.services.sns.SnsAsyncClient

/**
 * Spring Boot 4 auto-configuration for AWS SNS.
 *
 * ## Contract
 *
 * Registers an [SnsAsyncClient] and [SnsOperations] when the AWS SNS SDK is on
 * the runtime classpath and `bluetape4k.aws.sns.enabled` is not disabled.
 *
 * ```kotlin
 * @Bean
 * fun publisher(sns: SnsOperations): OrderPublisher = OrderPublisher(sns)
 * ```
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.services.sns.SnsAsyncClient",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.sns", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SnsProperties::class)
class SnsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun snsAsyncClient(
        properties: SnsProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
    ): SnsAsyncClient =
        SnsAsyncClient.builder()
            .credentialsProvider(resolveCredentialsProvider(credentialsProvider))
            .apply {
                properties.region?.let { region(Region.of(it)) }
                properties.endpointOverride?.let { endpointOverride(it) }
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .build()

    @Bean
    @ConditionalOnMissingBean(SnsOperations::class)
    fun snsCoroutinesTemplate(
        snsAsyncClient: SnsAsyncClient,
        properties: SnsProperties,
    ): SnsCoroutinesTemplate =
        SnsCoroutinesTemplate(snsAsyncClient, properties)

    private fun resolveCredentialsProvider(
        provider: ObjectProvider<AwsCredentialsProvider>,
    ): AwsCredentialsProvider =
        provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }
}
