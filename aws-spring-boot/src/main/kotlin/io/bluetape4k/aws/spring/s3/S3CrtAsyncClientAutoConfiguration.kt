package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import io.bluetape4k.aws.spring.connection.AwsServiceConnectionDetails
import io.bluetape4k.aws.spring.connection.S3ConnectionDetails
import io.bluetape4k.aws.spring.resolveAwsCredentialsProvider
import io.bluetape4k.aws.spring.resolveServiceClientDefaults
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3CrtAsyncClientBuilder

/**
 * AWS CRT 기반 S3 async client를 opt-in으로 구성합니다.
 *
 * CRT dependency가 없거나 `bluetape4k.aws.s3.crt.enabled=false`이면 기존
 * [S3AutoConfiguration]의 Netty/SDK client 경로가 그대로 유지됩니다. 명시적
 * [S3AsyncClient] Bean은 항상 우선하며, 생성된 client는 Spring lifecycle에서 닫힙니다.
 */
@AutoConfiguration(
    before = [S3AutoConfiguration::class],
    after = [AwsAutoConfiguration::class],
)
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.services.s3.S3AsyncClient",
        "software.amazon.awssdk.services.s3.S3CrtAsyncClientBuilder",
        "software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient",
    ]
)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.s3.crt",
    name = ["enabled"],
    havingValue = "true",
)
@EnableConfigurationProperties(S3CrtClientProperties::class)
@Suppress("DEPRECATION")
class S3CrtAsyncClientAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(S3AsyncClient::class)
    fun s3CrtAsyncClient(
        awsProperties: ObjectProvider<AwsProperties>,
        s3Properties: S3Properties,
        crtProperties: S3CrtClientProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        connectionDetails: ObjectProvider<AwsServiceConnectionDetails>,
        serviceConnectionDetails: ObjectProvider<S3ConnectionDetails>,
        customizers: ObjectProvider<AwsClientCustomizer<S3CrtAsyncClientBuilder>>,
    ): S3AsyncClient {
        val defaults = resolveServiceClientDefaults(
            connectionDetails = serviceConnectionDetails,
            awsProperties = awsProperties,
            serviceName = "s3",
            serviceRegion = s3Properties.region,
            serviceEndpointOverride = s3Properties.endpointOverride,
        )
        val builder = S3AsyncClient.crtBuilder()
            .credentialsProvider(resolveAwsCredentialsProvider(credentialsProvider, connectionDetails))
            .forcePathStyle(s3Properties.pathStyleAccessEnabled)
            .accelerate(s3Properties.accelerateModeEnabled)

        defaults.region?.let(builder::region)
        defaults.endpointOverride?.let(builder::endpointOverride)
        crtProperties.targetThroughputInGbps?.let(builder::targetThroughputInGbps)
        crtProperties.maxConcurrency?.let(builder::maxConcurrency)
        crtProperties.minimumPartSizeInBytes?.let(builder::minimumPartSizeInBytes)
        crtProperties.initialReadBufferSizeInBytes?.let(builder::initialReadBufferSizeInBytes)
        crtProperties.thresholdInBytes?.let(builder::thresholdInBytes)
        crtProperties.maxNativeMemoryLimitInBytes?.let(builder::maxNativeMemoryLimitInBytes)
        crtProperties.checksumValidationEnabled?.let(builder::checksumValidationEnabled)
        crtProperties.requestChecksumCalculation?.let(builder::requestChecksumCalculation)
        crtProperties.responseChecksumValidation?.let(builder::responseChecksumValidation)
        customizers.orderedStream().forEach { it.customize(builder) }

        return builder.build()
    }
}
