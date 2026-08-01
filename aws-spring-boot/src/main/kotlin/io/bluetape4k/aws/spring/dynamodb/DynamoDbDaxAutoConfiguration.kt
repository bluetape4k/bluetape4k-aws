package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.resolveClientDefaults
import io.bluetape4k.support.requireNotNull
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.dax.ClusterDaxAsyncClient
import software.amazon.dax.Configuration
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * DAX SDK가 있고 DAX를 명시적으로 활성화하면 DAX 기반 [DynamoDbAsyncClient]를 자동 구성합니다.
 *
 * ## 계약
 *
 * Bean 타입은 [DynamoDbAsyncClient]로 유지되므로 기존
 * [software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient]와 코루틴 리포지토리가
 * 리포지토리 API 변경 없이 DAX를 사용할 수 있습니다.
 */
@AutoConfiguration(
    after = [AwsAutoConfiguration::class],
    before = [DynamoDbAutoConfiguration::class],
)
@ConditionalOnAwsEnabled
@ConditionalOnClass(name = ["software.amazon.dax.ClusterDaxAsyncClient"])
@ConditionalOnProperty(prefix = DYNAMODB_PROPERTIES_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = DYNAMODB_DAX_PROPERTIES_PREFIX, name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(DynamoDbProperties::class)
class DynamoDbDaxAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DynamoDbAsyncClient::class)
    fun dynamoDbDaxAsyncClient(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: DynamoDbProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
    ): DynamoDbAsyncClient {
        val dax = properties.dax
        dax.validateEnabled()

        val defaults = resolveDynamoDbAwsProperties(awsProperties).resolveClientDefaults(
            properties.region,
            properties.endpointOverride,
        )
        val region = dax.region?.let { Region.of(it) } ?: defaults.region
        region.requireNotNull("$DYNAMODB_DAX_PROPERTIES_PREFIX.region")

        val configuration = with(dax) {
            Configuration.builder()
                .url(url.toString())
                .region(region)
                .credentialsProvider(resolveDynamoDbCredentialsProvider(credentialsProvider))
                .connectTimeoutMillis(connectTimeout.toMillisInt("$DYNAMODB_DAX_PROPERTIES_PREFIX.connect-timeout"))
                .requestTimeoutMillis(requestTimeout.toMillisInt("$DYNAMODB_DAX_PROPERTIES_PREFIX.request-timeout"))
                .idleTimeoutMillis(idleTimeout.toMillisInt("$DYNAMODB_DAX_PROPERTIES_PREFIX.idle-timeout"))
                .connectionTtlMillis(connectionTtl.toMillisInt("$DYNAMODB_DAX_PROPERTIES_PREFIX.connection-ttl"))
                .writeRetries(writeRetries)
                .readRetries(readRetries)
                .clusterUpdateIntervalMillis(
                    clusterUpdateInterval.toMillisInt("$DYNAMODB_DAX_PROPERTIES_PREFIX.cluster-update-interval")
                )
                .endpointRefreshTimeoutMillis(
                    endpointRefreshTimeout.toMillisInt("$DYNAMODB_DAX_PROPERTIES_PREFIX.endpoint-refresh-timeout")
                )
                .maxConcurrency(maxConcurrency)
                .maxPendingConnectionAcquires(maxPendingConnectionAcquires)
                .skipHostNameVerification(skipHostNameVerification)
                .build()
        }

        return ClusterDaxAsyncClient.builder()
            .overrideConfiguration(configuration)
            .build()
    }
}
