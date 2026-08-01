package io.bluetape4k.aws.spring.s3.accessgrants

import io.bluetape4k.aws.spring.AwsAsyncClientCustomizer
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.applyGlobalCustomizers
import io.bluetape4k.aws.spring.applyServiceCustomizers
import io.bluetape4k.aws.spring.resolveClientDefaults
import io.bluetape4k.aws.spring.s3.S3AutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.services.s3control.S3ControlAsyncClient
import software.amazon.awssdk.services.s3control.S3ControlAsyncClientBuilder
import software.amazon.awssdk.services.s3control.S3ControlClient
import software.amazon.awssdk.services.s3control.S3ControlClientBuilder
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * S3 Control을 통한 S3 Access Grants용 Spring Boot 4 자동 구성입니다.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class, S3AutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.SdkHttpClient",
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.services.s3control.S3ControlClient",
        "software.amazon.awssdk.services.s3control.S3ControlAsyncClient",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.s3", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = S3_ACCESS_GRANTS_PROPERTIES_PREFIX, name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(S3AccessGrantsProperties::class)
class S3AccessGrantsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun s3ControlClient(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: S3AccessGrantsProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkHttpClient>,
        globalCustomizers: ObjectProvider<AwsSyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<S3ControlClientBuilder>>,
    ): S3ControlClient =
        S3ControlClient.builder()
            .credentialsProvider(resolveCredentialsProvider(credentialsProvider))
            .applyAwsDefaults(
                resolveAwsProperties(awsProperties).resolveClientDefaults(properties.region, properties.endpointOverride)
            )
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .also { it.applyGlobalCustomizers("s3control", globalCustomizers) }
            .applyServiceCustomizers(serviceCustomizers)
            .build()

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun s3ControlAsyncClient(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: S3AccessGrantsProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
        globalCustomizers: ObjectProvider<AwsAsyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<S3ControlAsyncClientBuilder>>,
    ): S3ControlAsyncClient =
        S3ControlAsyncClient.builder()
            .credentialsProvider(resolveCredentialsProvider(credentialsProvider))
            .applyAwsDefaults(
                resolveAwsProperties(awsProperties).resolveClientDefaults(properties.region, properties.endpointOverride)
            )
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .also { it.applyGlobalCustomizers("s3control", globalCustomizers) }
            .applyServiceCustomizers(serviceCustomizers)
            .build()

    @Bean
    @ConditionalOnMissingBean(S3AccessGrantsOperations::class)
    fun s3AccessGrantsOperations(
        s3ControlAsyncClient: S3ControlAsyncClient,
    ): S3AccessGrantsCoroutinesTemplate =
        S3AccessGrantsCoroutinesTemplate(s3ControlAsyncClient)

    private fun resolveCredentialsProvider(
        provider: ObjectProvider<AwsCredentialsProvider>,
    ): AwsCredentialsProvider =
        provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }

    private fun resolveAwsProperties(provider: ObjectProvider<AwsProperties>): AwsProperties =
        provider.getIfAvailable { AwsProperties() }
}
