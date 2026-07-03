package io.bluetape4k.aws.spring.sqs

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
import org.springframework.core.env.Environment
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * Spring Boot 4 auto-configuration for SQS clients, operations, and listener infrastructure.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.services.sqs.SqsAsyncClient",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.sqs", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SqsProperties::class)
class SqsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun sqsAsyncClient(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: SqsProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
        globalCustomizers: ObjectProvider<AwsAsyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<SqsAsyncClientBuilder>>,
    ): SqsAsyncClient =
        SqsAsyncClient.builder()
            .credentialsProvider(resolveCredentialsProvider(credentialsProvider))
            .applyAwsDefaults(
                resolveAwsProperties(awsProperties).resolveClientDefaults(properties.region, properties.endpointOverride)
            )
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .also { it.applyGlobalCustomizers("sqs", globalCustomizers) }
            .applyServiceCustomizers(serviceCustomizers)
            .build()

    @Bean
    @ConditionalOnMissingBean(SqsOperations::class)
    fun sqsCoroutinesTemplate(
        sqsAsyncClient: SqsAsyncClient,
        properties: SqsProperties,
    ): SqsCoroutinesTemplate =
        SqsCoroutinesTemplate(sqsAsyncClient, properties)

    @Bean
    @ConditionalOnMissingBean
    fun sqsMessageListenerContainerRegistry(): SqsMessageListenerContainerRegistry =
        SqsMessageListenerContainerRegistry()

    @Bean
    @ConditionalOnMissingBean
    fun sqsListenerAnnotationBeanPostProcessor(
        environment: Environment,
        properties: SqsProperties,
        operations: SqsOperations,
        registry: SqsMessageListenerContainerRegistry,
        messageConverter: ObjectProvider<SqsMessageConverter>,
        interceptors: ObjectProvider<SqsListenerInterceptor>,
    ): SqsListenerAnnotationBeanPostProcessor =
        SqsListenerAnnotationBeanPostProcessor(
            environment = environment,
            properties = properties,
            operations = operations,
            registry = registry,
            messageConverter = messageConverter.getIfAvailable { NoopSqsMessageConverter },
            interceptors = interceptors.orderedStream().toList(),
        )

    private fun resolveCredentialsProvider(
        provider: ObjectProvider<AwsCredentialsProvider>,
    ): AwsCredentialsProvider =
        provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }

    private fun resolveAwsProperties(provider: ObjectProvider<AwsProperties>): AwsProperties =
        provider.getIfAvailable { AwsProperties() }
}
