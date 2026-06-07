package io.bluetape4k.aws.spring.imds

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import software.amazon.awssdk.imds.Ec2MetadataRetryPolicy

/**
 * Spring Boot 4 auto-configuration for EC2 Instance Metadata Service access.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.imds.Ec2MetadataAsyncClient",
    ]
)
@ConditionalOnProperty(prefix = IMDS_PROPERTIES_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ImdsProperties::class)
class ImdsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun ec2MetadataAsyncClient(
        properties: ImdsProperties,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
        customizers: ObjectProvider<AwsClientCustomizer<Ec2MetadataAsyncClient.Builder>>,
    ): Ec2MetadataAsyncClient =
        Ec2MetadataAsyncClient.builder()
            .tokenTtl(properties.tokenTtl)
            .retryPolicy(resolveRetryPolicy(properties))
            .apply {
                properties.endpoint?.let { endpoint(it) }
                    ?: properties.endpointMode?.let { endpointMode(it) }
                httpClient(httpClient.getIfAvailable() ?: SdkAsyncHttpClientProvider.defaultHttpClient)
                customizers.orderedStream().forEach { it.customize(this) }
            }
            .build()

    @Bean
    @ConditionalOnMissingBean(ImdsOperations::class)
    fun imdsCoroutinesTemplate(
        ec2MetadataAsyncClient: Ec2MetadataAsyncClient,
        properties: ImdsProperties,
    ): ImdsCoroutinesTemplate =
        ImdsCoroutinesTemplate(ec2MetadataAsyncClient, properties)

    private fun resolveRetryPolicy(properties: ImdsProperties): Ec2MetadataRetryPolicy =
        if (properties.retries == 0) {
            Ec2MetadataRetryPolicy.none()
        } else {
            Ec2MetadataRetryPolicy.builder()
                .numRetries(properties.retries)
                .build()
        }
}
