package io.bluetape4k.aws.spring.cloudwatch

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
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClientBuilder

/**
 * Spring Boot 4 auto-configuration for CloudWatch Logs event publishing.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient",
    ]
)
@ConditionalOnProperty(
    prefix = CLOUDWATCH_LOGS_PROPERTIES_PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(CloudWatchLogsProperties::class)
class CloudWatchLogsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun cloudWatchLogsAsyncClient(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: CloudWatchLogsProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
        globalCustomizers: ObjectProvider<AwsAsyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<CloudWatchLogsAsyncClientBuilder>>,
    ): CloudWatchLogsAsyncClient =
        CloudWatchLogsAsyncClient.builder()
            .credentialsProvider(resolveCredentialsProvider(credentialsProvider))
            .applyAwsDefaults(
                resolveAwsProperties(awsProperties).resolveClientDefaults(properties.region, properties.endpointOverride)
            )
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .also { it.applyGlobalCustomizers("cloudwatchlogs", globalCustomizers) }
            .applyServiceCustomizers(serviceCustomizers)
            .build()

    @Bean
    @ConditionalOnMissingBean(CloudWatchLogsOperations::class)
    fun cloudWatchLogsCoroutinesTemplate(
        cloudWatchLogsAsyncClient: CloudWatchLogsAsyncClient,
        properties: CloudWatchLogsProperties,
    ): CloudWatchLogsCoroutinesTemplate =
        CloudWatchLogsCoroutinesTemplate(cloudWatchLogsAsyncClient, properties)

    private fun resolveCredentialsProvider(
        provider: ObjectProvider<AwsCredentialsProvider>,
    ): AwsCredentialsProvider =
        provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }

    private fun resolveAwsProperties(provider: ObjectProvider<AwsProperties>): AwsProperties =
        provider.getIfAvailable { AwsProperties() }
}
