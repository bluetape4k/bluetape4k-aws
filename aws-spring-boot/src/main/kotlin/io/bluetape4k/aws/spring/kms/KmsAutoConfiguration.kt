package io.bluetape4k.aws.spring.kms

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
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsAsyncClient

/**
 * Spring Boot auto-configuration for AWS KMS.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.services.kms.KmsAsyncClient",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.kms", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KmsProperties::class)
class KmsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun kmsAsyncClient(
        properties: KmsProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
    ): KmsAsyncClient =
        KmsAsyncClient.builder()
            .credentialsProvider(resolveCredentialsProvider(credentialsProvider))
            .apply {
                properties.region?.let { region(Region.of(it)) }
                properties.endpointOverride?.let { endpointOverride(it) }
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun dataKeyCache(properties: KmsProperties): DataKeyCache =
        if (properties.dataKeyCache.enabled) {
            InMemoryDataKeyCache(
                maxSize = properties.dataKeyCache.maxSize,
                ttl = properties.dataKeyCache.ttl,
            )
        } else {
            NoopDataKeyCache
        }

    @Bean
    @ConditionalOnMissingBean(KmsOperations::class)
    fun kmsCoroutinesEncryptor(
        kmsAsyncClient: KmsAsyncClient,
        properties: KmsProperties,
        dataKeyCache: DataKeyCache,
    ): KmsCoroutinesEncryptor =
        KmsCoroutinesEncryptor(kmsAsyncClient, properties, dataKeyCache)

    private fun resolveCredentialsProvider(
        provider: ObjectProvider<AwsCredentialsProvider>,
    ): AwsCredentialsProvider =
        provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }
}
