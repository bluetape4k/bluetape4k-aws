package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsAsyncClientCustomizer
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.applyGlobalCustomizers
import io.bluetape4k.aws.spring.applyServiceCustomizers
import io.bluetape4k.aws.spring.resolveAwsCredentialsProvider
import io.bluetape4k.aws.spring.resolveServiceClientDefaults
import io.bluetape4k.aws.spring.connection.AwsServiceConnectionDetails
import io.bluetape4k.aws.spring.connection.SnsConnectionDetails
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.SnsAsyncClientBuilder
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * AWS SNS용 Spring Boot 4 자동 구성입니다.
 *
 * ## 계약
 *
 * 런타임 클래스패스에 AWS SNS SDK가 있고 `bluetape4k.aws.sns.enabled`를 비활성화하지 않았으면
 * [SnsAsyncClient]와 [SnsOperations]를 등록합니다.
 *
 * ```kotlin
 * @Bean
 * fun publisher(sns: SnsOperations): OrderPublisher = OrderPublisher(sns)
 * ```
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.services.sns.SnsAsyncClient",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.sns", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SnsProperties::class)
class SnsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun snsAsyncClient(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: SnsProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        connectionDetails: ObjectProvider<AwsServiceConnectionDetails>,
        serviceConnectionDetails: ObjectProvider<SnsConnectionDetails>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
        globalCustomizers: ObjectProvider<AwsAsyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<SnsAsyncClientBuilder>>,
    ): SnsAsyncClient =
        SnsAsyncClient.builder()
            .credentialsProvider(resolveAwsCredentialsProvider(credentialsProvider, connectionDetails))
            .applyAwsDefaults(
                resolveServiceClientDefaults(
                    serviceConnectionDetails,
                    awsProperties,
                    "sns",
                    properties.region,
                    properties.endpointOverride,
                )
            )
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .also { it.applyGlobalCustomizers("sns", globalCustomizers) }
            .applyServiceCustomizers(serviceCustomizers)
            .build()

    @Bean
    @ConditionalOnMissingBean(SnsTopicArnCache::class)
    fun snsTopicArnCache(properties: SnsProperties): SnsTopicArnCache =
        if (properties.topicArnCache.enabled) {
            InMemorySnsTopicArnCache(
                maxSize = properties.topicArnCache.maxSize,
                ttl = properties.topicArnCache.ttl,
            )
        } else {
            NoopSnsTopicArnCache
        }

    /**
     * 최종 SDK client identity와 resolver scope가 달라지지 않도록 검증합니다.
     * 명시된 endpoint/region을 바꾸는 customizer는 fail-fast하며, region을
     * 설정하지 않은 경우에는 AWS SDK가 선택한 최종 region을 resolver scope로 사용합니다.
     * 그런 client에는 명시적인 custom resolver bean을 함께 제공해야 합니다.
     */
    @Bean
    @ConditionalOnMissingBean(SnsTopicArnResolver::class)
    fun snsTopicArnResolver(
        snsAsyncClient: SnsAsyncClient,
        properties: SnsProperties,
        awsProperties: ObjectProvider<AwsProperties>,
        cache: SnsTopicArnCache,
        serviceConnectionDetails: ObjectProvider<SnsConnectionDetails>,
    ): SnsTopicArnResolver {
        val defaults = resolveServiceClientDefaults(
            connectionDetails = serviceConnectionDetails,
            awsProperties = awsProperties,
            serviceName = "sns",
            serviceRegion = properties.region,
            serviceEndpointOverride = properties.endpointOverride,
        )
        val clientIdentity = runCatching {
            val clientConfiguration = snsAsyncClient.serviceClientConfiguration()
            clientConfiguration.region() to clientConfiguration.endpointOverride().orElse(null)
        }.getOrElse { cause ->
            throw IllegalStateException(
                "SNS client identity is unavailable; provide a custom SnsTopicArnResolver " +
                    "for a client with an uninspectable configuration.",
                cause,
            )
        }
        val actualRegion = clientIdentity.first
        val actualEndpointOverride = clientIdentity.second
        require(
            (defaults.region == null || actualRegion == defaults.region) &&
                actualEndpointOverride == defaults.endpointOverride,
        ) {
            "SNS client customizer must not change explicitly configured endpoint or region after AWS defaults " +
                "are applied; " +
                "provide a custom SnsTopicArnResolver for a different client identity."
        }
        val scope = SnsTopicArnResolverScope(
            endpointOverride = actualEndpointOverride,
            region = actualRegion?.id(),
            accountId = properties.accountId,
        )
        return SnsTopicArnResolver(
            snsAsyncClient = snsAsyncClient,
            cache = cache,
            scope = scope,
            allowCrossAccountTopicArn = properties.allowCrossAccountTopicArn,
        )
    }

    @Bean
    @ConditionalOnMissingBean(SnsOperations::class)
    fun snsCoroutinesTemplate(
        snsAsyncClient: SnsAsyncClient,
        properties: SnsProperties,
        topicArnResolver: SnsTopicArnResolver,
    ): SnsCoroutinesTemplate =
        SnsCoroutinesTemplate(
            snsAsyncClient,
            properties,
            topicArnResolver,
            DefaultSnsBatchExecutionStrategy,
        )

}
