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
