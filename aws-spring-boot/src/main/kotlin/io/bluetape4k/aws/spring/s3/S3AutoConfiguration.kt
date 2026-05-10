package io.bluetape4k.aws.spring.s3

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
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
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
        properties: S3Properties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkHttpClient>,
    ): S3Client =
        S3Client.builder()
            .applyCommon(properties, resolveCredentialsProvider(credentialsProvider))
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun s3AsyncClient(
        properties: S3Properties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
    ): S3AsyncClient =
        S3AsyncClient.builder()
            .applyCommon(properties, resolveCredentialsProvider(credentialsProvider))
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun s3Presigner(
        properties: S3Properties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
    ): S3Presigner =
        S3Presigner.builder()
            .applyCommon(properties, resolveCredentialsProvider(credentialsProvider))
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

    private fun resolveCredentialsProvider(
        provider: ObjectProvider<AwsCredentialsProvider>,
    ): AwsCredentialsProvider =
        provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }

    private fun S3ClientBuilder.applyCommon(
        properties: S3Properties,
        credentialsProvider: AwsCredentialsProvider,
    ): S3ClientBuilder =
        credentialsProvider(credentialsProvider).apply {
            properties.region?.let { region(Region.of(it)) }
            properties.endpointOverride?.let { endpointOverride(it) }
            serviceConfiguration(s3Configuration(properties))
        }

    private fun S3AsyncClientBuilder.applyCommon(
        properties: S3Properties,
        credentialsProvider: AwsCredentialsProvider,
    ): S3AsyncClientBuilder =
        credentialsProvider(credentialsProvider).apply {
            properties.region?.let { region(Region.of(it)) }
            properties.endpointOverride?.let { endpointOverride(it) }
            serviceConfiguration(s3Configuration(properties))
        }

    private fun S3Presigner.Builder.applyCommon(
        properties: S3Properties,
        credentialsProvider: AwsCredentialsProvider,
    ): S3Presigner.Builder =
        credentialsProvider(credentialsProvider).apply {
            properties.region?.let { region(Region.of(it)) }
            properties.endpointOverride?.let { endpointOverride(it) }
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
