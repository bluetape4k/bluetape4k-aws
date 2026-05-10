package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.AwsAutoConfiguration
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
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient

/**
 * SQS용 Spring Boot 4 자동 설정.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
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
        properties: SqsProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
    ): SqsAsyncClient =
        SqsAsyncClient.builder()
            .credentialsProvider(resolveCredentialsProvider(credentialsProvider))
            .apply {
                properties.region?.let { region(Region.of(it)) }
                properties.endpointOverride?.let { endpointOverride(it) }
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
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
    ): SqsListenerAnnotationBeanPostProcessor =
        SqsListenerAnnotationBeanPostProcessor(environment, properties, operations, registry)

    private fun resolveCredentialsProvider(
        provider: ObjectProvider<AwsCredentialsProvider>,
    ): AwsCredentialsProvider =
        provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }
}
