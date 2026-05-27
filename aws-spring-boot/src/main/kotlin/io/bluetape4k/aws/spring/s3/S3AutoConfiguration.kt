package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.AwsAsyncClientCustomizer
import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
import io.bluetape4k.aws.spring.kms.KmsOperations
import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.applyGlobalCustomizers
import io.bluetape4k.aws.spring.applyServiceCustomizers
import io.bluetape4k.aws.spring.resolveClientDefaults
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3ClientBuilder
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner

@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.SdkHttpClient",
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.services.s3.S3Client",
        "software.amazon.awssdk.services.s3.S3AsyncClient",
        "software.amazon.awssdk.services.s3.presigner.S3Presigner",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.s3", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(S3Properties::class)
class S3AutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun s3Client(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: S3Properties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkHttpClient>,
        globalCustomizers: ObjectProvider<AwsSyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<S3ClientBuilder>>,
    ): S3Client =
        S3Client.builder()
            .applyCommon(resolveAwsProperties(awsProperties), properties, resolveCredentialsProvider(credentialsProvider))
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .also { it.applyGlobalCustomizers("s3", globalCustomizers) }
            .applyServiceCustomizers(serviceCustomizers)
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun s3AsyncClient(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: S3Properties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
        globalCustomizers: ObjectProvider<AwsAsyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<S3AsyncClientBuilder>>,
    ): S3AsyncClient =
        S3AsyncClient.builder()
            .applyCommon(resolveAwsProperties(awsProperties), properties, resolveCredentialsProvider(credentialsProvider))
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .also { it.applyGlobalCustomizers("s3", globalCustomizers) }
            .applyServiceCustomizers(serviceCustomizers)
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun s3Presigner(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: S3Properties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<S3Presigner.Builder>>,
    ): S3Presigner =
        S3Presigner.builder()
            .applyCommon(resolveAwsProperties(awsProperties), properties, resolveCredentialsProvider(credentialsProvider))
            .applyServiceCustomizers(serviceCustomizers)
            .build()

    @Bean
    @ConditionalOnMissingBean(S3Operations::class)
    fun s3CoroutinesTemplate(
        s3AsyncClient: S3AsyncClient,
        s3Client: S3Client,
        s3Presigner: S3Presigner,
        properties: S3Properties,
    ): S3CoroutinesTemplate =
        S3CoroutinesTemplate(s3AsyncClient, s3Client, s3Presigner, properties)

    @Bean
    @ConditionalOnBean(KmsOperations::class)
    @ConditionalOnMissingBean(S3ClientSideEncryptionOperations::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.s3.client-side-encryption",
        name = ["enabled"],
        havingValue = "true",
    )
    fun s3ClientSideEncryptionOperations(
        s3AsyncClient: S3AsyncClient,
        kmsOperations: KmsOperations,
        properties: S3Properties,
    ): S3ClientSideEncryptionOperations =
        S3ClientSideEncryptionTemplate(s3AsyncClient, kmsOperations, properties)

    private fun resolveCredentialsProvider(
        provider: ObjectProvider<AwsCredentialsProvider>,
    ): AwsCredentialsProvider =
        provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }

    private fun resolveAwsProperties(provider: ObjectProvider<AwsProperties>): AwsProperties =
        provider.getIfAvailable { AwsProperties() }

    private fun S3ClientBuilder.applyCommon(
        awsProperties: AwsProperties,
        properties: S3Properties,
        credentialsProvider: AwsCredentialsProvider,
    ): S3ClientBuilder =
        credentialsProvider(credentialsProvider).apply {
            applyAwsDefaults(awsProperties.resolveClientDefaults(properties.region, properties.endpointOverride))
            serviceConfiguration(s3Configuration(properties))
        }

    private fun S3AsyncClientBuilder.applyCommon(
        awsProperties: AwsProperties,
        properties: S3Properties,
        credentialsProvider: AwsCredentialsProvider,
    ): S3AsyncClientBuilder =
        credentialsProvider(credentialsProvider).apply {
            applyAwsDefaults(awsProperties.resolveClientDefaults(properties.region, properties.endpointOverride))
            serviceConfiguration(s3Configuration(properties))
        }

    private fun S3Presigner.Builder.applyCommon(
        awsProperties: AwsProperties,
        properties: S3Properties,
        credentialsProvider: AwsCredentialsProvider,
    ): S3Presigner.Builder =
        credentialsProvider(credentialsProvider).apply {
            val defaults = awsProperties.resolveClientDefaults(properties.region, properties.endpointOverride)
            defaults.region?.let { region(it) }
            defaults.endpointOverride?.let { endpointOverride(it) }
            serviceConfiguration(s3Configuration(properties))
        }

    private fun s3Configuration(properties: S3Properties): S3Configuration =
        S3Configuration.builder()
            .pathStyleAccessEnabled(properties.pathStyleAccessEnabled)
            .accelerateModeEnabled(properties.accelerateModeEnabled)
            .apply {
                properties.chunkedEncodingEnabled?.let { chunkedEncodingEnabled(it) }
            }
            .build()
}
