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
import io.bluetape4k.aws.spring.resolveAwsCredentialsProvider
import io.bluetape4k.aws.spring.resolveServiceClientDefaults
import io.bluetape4k.aws.spring.connection.AwsServiceConnectionDetails
import io.bluetape4k.aws.spring.connection.S3ConnectionDetails
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3ClientBuilder
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import java.util.Locale

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@org.springframework.context.annotation.Conditional(S3CseProviderCondition::class)
internal annotation class ConditionalOnS3CseProvider(
    val value: ClientSideEncryptionProvider,
)

internal class S3CseProviderCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean {
        val attributes = metadata.getAnnotationAttributes(ConditionalOnS3CseProvider::class.java.name)
        val requested = attributes?.get("value") as? ClientSideEncryptionProvider
        val configured = context.environment
            .getProperty("bluetape4k.aws.s3.client-side-encryption.provider")
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?: ClientSideEncryptionProvider.KMS.name
        return requested != null && configured == requested.name
    }
}

@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnAwsEnabled
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
        connectionDetails: ObjectProvider<AwsServiceConnectionDetails>,
        serviceConnectionDetails: ObjectProvider<S3ConnectionDetails>,
        httpClient: ObjectProvider<SdkHttpClient>,
        globalCustomizers: ObjectProvider<AwsSyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<S3ClientBuilder>>,
    ): S3Client =
        S3Client.builder()
            .applyCommon(
                resolveServiceClientDefaults(
                    serviceConnectionDetails,
                    awsProperties,
                    "s3",
                    properties.region,
                    properties.endpointOverride,
                ),
                properties,
                resolveAwsCredentialsProvider(credentialsProvider, connectionDetails),
            )
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
        connectionDetails: ObjectProvider<AwsServiceConnectionDetails>,
        serviceConnectionDetails: ObjectProvider<S3ConnectionDetails>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
        globalCustomizers: ObjectProvider<AwsAsyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<S3AsyncClientBuilder>>,
    ): S3AsyncClient =
        S3AsyncClient.builder()
            .applyCommon(
                resolveServiceClientDefaults(
                    serviceConnectionDetails,
                    awsProperties,
                    "s3",
                    properties.region,
                    properties.endpointOverride,
                ),
                properties,
                resolveAwsCredentialsProvider(credentialsProvider, connectionDetails),
            )
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
        connectionDetails: ObjectProvider<AwsServiceConnectionDetails>,
        serviceConnectionDetails: ObjectProvider<S3ConnectionDetails>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<S3Presigner.Builder>>,
    ): S3Presigner =
        S3Presigner.builder()
            .applyCommon(
                resolveServiceClientDefaults(
                    serviceConnectionDetails,
                    awsProperties,
                    "s3",
                    properties.region,
                    properties.endpointOverride,
                ),
                properties,
                resolveAwsCredentialsProvider(credentialsProvider, connectionDetails),
            )
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
    @ConditionalOnS3CseProvider(ClientSideEncryptionProvider.KMS)
    fun s3ClientSideEncryptionOperations(
        s3AsyncClient: S3AsyncClient,
        kmsOperations: KmsOperations,
        properties: S3Properties,
    ): S3ClientSideEncryptionOperations =
        S3ClientSideEncryptionTemplate(s3AsyncClient, kmsOperations, properties)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(S3ClientSideEncryptionOperations::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.s3.client-side-encryption",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnS3CseProvider(ClientSideEncryptionProvider.AES)
    fun s3AesClientSideEncryptionOperations(
        s3AsyncClient: S3AsyncClient,
        aesProvider: ObjectProvider<S3AesProvider>,
        properties: S3Properties,
    ): S3ClientSideEncryptionProviderTemplate =
        S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient = s3AsyncClient,
            properties = properties,
            aesProvider = aesProvider.getIfUnique() ?: error(
                "S3AesProvider is required exactly once when " +
                    "bluetape4k.aws.s3.client-side-encryption.provider=AES.",
            ),
        )

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(S3ClientSideEncryptionOperations::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.s3.client-side-encryption",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnS3CseProvider(ClientSideEncryptionProvider.RSA)
    fun s3RsaClientSideEncryptionOperations(
        s3AsyncClient: S3AsyncClient,
        rsaProvider: ObjectProvider<S3RsaProvider>,
        properties: S3Properties,
    ): S3ClientSideEncryptionProviderTemplate =
        S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient = s3AsyncClient,
            properties = properties,
            rsaProvider = rsaProvider.getIfUnique() ?: error(
                "S3RsaProvider is required exactly once when " +
                    "bluetape4k.aws.s3.client-side-encryption.provider=RSA.",
            ),
        )

    private fun S3ClientBuilder.applyCommon(
        defaults: io.bluetape4k.aws.spring.AwsClientDefaults,
        properties: S3Properties,
        credentialsProvider: AwsCredentialsProvider,
    ): S3ClientBuilder =
        credentialsProvider(credentialsProvider).apply {
            applyAwsDefaults(defaults)
            serviceConfiguration(s3Configuration(properties))
        }

    private fun S3AsyncClientBuilder.applyCommon(
        defaults: io.bluetape4k.aws.spring.AwsClientDefaults,
        properties: S3Properties,
        credentialsProvider: AwsCredentialsProvider,
    ): S3AsyncClientBuilder =
        credentialsProvider(credentialsProvider).apply {
            applyAwsDefaults(defaults)
            serviceConfiguration(s3Configuration(properties))
        }

    private fun S3Presigner.Builder.applyCommon(
        defaults: io.bluetape4k.aws.spring.AwsClientDefaults,
        properties: S3Properties,
        credentialsProvider: AwsCredentialsProvider,
    ): S3Presigner.Builder =
        credentialsProvider(credentialsProvider).apply {
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
