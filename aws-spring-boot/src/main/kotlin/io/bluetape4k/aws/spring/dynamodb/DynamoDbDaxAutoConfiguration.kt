package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.resolveClientDefaults
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

/**
 * Auto-configures a DAX-backed [DynamoDbAsyncClient] when the DAX SDK is present
 * and DAX is explicitly enabled.
 *
 * ## Contract
 *
 * The bean type remains [DynamoDbAsyncClient], allowing the existing
 * [software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient] and
 * coroutine repositories to use DAX without repository API changes.
 */
@AutoConfiguration(
    after = [AwsAutoConfiguration::class],
    before = [DynamoDbAutoConfiguration::class],
)
@ConditionalOnClass(name = ["software.amazon.dax.ClusterDaxAsyncClient"])
@ConditionalOnProperty(prefix = "bluetape4k.aws.dynamodb", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "bluetape4k.aws.dynamodb.dax", name = ["enabled"], havingValue = "true")
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
        require(region != null) {
            "AWS region is required when DAX is enabled."
        }

        val configuration = with(dax) {
            Configuration.builder()
                .url(url.toString())
                .region(region)
                .credentialsProvider(resolveDynamoDbCredentialsProvider(credentialsProvider))
                .connectTimeoutMillis(connectTimeout.toMillisInt("bluetape4k.aws.dynamodb.dax.connect-timeout"))
                .requestTimeoutMillis(requestTimeout.toMillisInt("bluetape4k.aws.dynamodb.dax.request-timeout"))
                .idleTimeoutMillis(idleTimeout.toMillisInt("bluetape4k.aws.dynamodb.dax.idle-timeout"))
                .connectionTtlMillis(connectionTtl.toMillisInt("bluetape4k.aws.dynamodb.dax.connection-ttl"))
                .writeRetries(writeRetries)
                .readRetries(readRetries)
                .clusterUpdateIntervalMillis(
                    clusterUpdateInterval.toMillisInt("bluetape4k.aws.dynamodb.dax.cluster-update-interval")
                )
                .endpointRefreshTimeoutMillis(
                    endpointRefreshTimeout.toMillisInt("bluetape4k.aws.dynamodb.dax.endpoint-refresh-timeout")
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
