package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.aws.spring.AwsAsyncClientCustomizer
import io.bluetape4k.aws.spring.AwsAutoConfiguration
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
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * Spring Boot 4 auto-configuration for AWS Kinesis.
 *
 * ## Contract
 *
 * Registers a [KinesisAsyncClient] and [KinesisOperations] when the AWS Kinesis
 * SDK is on the runtime classpath and `bluetape4k.aws.kinesis.enabled` is not
 * disabled.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.services.kinesis.KinesisAsyncClient",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.kinesis", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KinesisProperties::class)
class KinesisAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun kinesisAsyncClient(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: KinesisProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
        globalCustomizers: ObjectProvider<AwsAsyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<KinesisAsyncClientBuilder>>,
    ): KinesisAsyncClient =
        KinesisAsyncClient.builder()
            .credentialsProvider(resolveCredentialsProvider(credentialsProvider))
            .applyAwsDefaults(
                resolveAwsProperties(awsProperties).resolveClientDefaults(properties.region, properties.endpointOverride)
            )
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .also { it.applyGlobalCustomizers("kinesis", globalCustomizers) }
            .applyServiceCustomizers(serviceCustomizers)
            .build()

    @Bean
    @ConditionalOnMissingBean(KinesisOperations::class)
    fun kinesisCoroutinesTemplate(
        kinesisAsyncClient: KinesisAsyncClient,
        properties: KinesisProperties,
    ): KinesisCoroutinesTemplate =
        KinesisCoroutinesTemplate(kinesisAsyncClient, properties)

    private fun resolveCredentialsProvider(
        provider: ObjectProvider<AwsCredentialsProvider>,
    ): AwsCredentialsProvider =
        provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }

    private fun resolveAwsProperties(provider: ObjectProvider<AwsProperties>): AwsProperties =
        provider.getIfAvailable { AwsProperties() }
}
