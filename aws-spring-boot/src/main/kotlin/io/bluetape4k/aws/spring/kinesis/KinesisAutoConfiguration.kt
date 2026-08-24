package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.aws.spring.AwsAsyncClientCustomizer
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.applyGlobalCustomizers
import io.bluetape4k.aws.spring.applyServiceCustomizers
import io.bluetape4k.aws.spring.resolveAwsCredentialsProvider
import io.bluetape4k.aws.spring.resolveServiceClientDefaults
import io.bluetape4k.aws.spring.connection.AwsServiceConnectionDetails
import io.bluetape4k.aws.spring.connection.KinesisConnectionDetails
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * AWS Kinesis용 Spring Boot 4 자동 구성입니다.
 *
 * ## 계약
 *
 * 런타임 클래스패스에 AWS Kinesis SDK가 있고 `bluetape4k.aws.kinesis.enabled`를
 * 비활성화하지 않았으면 [KinesisAsyncClient]와 [KinesisOperations]를 등록합니다.
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
        connectionDetails: ObjectProvider<AwsServiceConnectionDetails>,
        serviceConnectionDetails: ObjectProvider<KinesisConnectionDetails>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
        globalCustomizers: ObjectProvider<AwsAsyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<KinesisAsyncClientBuilder>>,
    ): KinesisAsyncClient =
        KinesisAsyncClient.builder()
            .credentialsProvider(resolveAwsCredentialsProvider(credentialsProvider, connectionDetails))
            .applyAwsDefaults(
                resolveServiceClientDefaults(
                    serviceConnectionDetails,
                    awsProperties,
                    "kinesis",
                    properties.region,
                    properties.endpointOverride,
                )
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

}
