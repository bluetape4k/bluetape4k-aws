package io.bluetape4k.aws.spring.dynamodb

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
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

/**
 * DynamoDB용 Spring Boot 4 자동 설정.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
        "software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient",
        "software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.dynamodb", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DynamoDbProperties::class)
class DynamoDbAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun dynamoDbAsyncClient(
        properties: DynamoDbProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
    ): DynamoDbAsyncClient =
        DynamoDbAsyncClient.builder()
            .credentialsProvider(resolveCredentialsProvider(credentialsProvider))
            .apply {
                properties.region?.let { region(Region.of(it)) }
                properties.endpointOverride?.let { endpointOverride(it) }
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun dynamoDbEnhancedAsyncClient(
        dynamoDbAsyncClient: DynamoDbAsyncClient,
    ): DynamoDbEnhancedAsyncClient =
        DynamoDbEnhancedAsyncClient.builder()
            .dynamoDbClient(dynamoDbAsyncClient)
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun dynamoDbTableNameResolver(properties: DynamoDbProperties): DynamoDbTableNameResolver =
        DefaultDynamoDbTableNameResolver(properties.tablePrefix)

    private fun resolveCredentialsProvider(
        provider: ObjectProvider<AwsCredentialsProvider>,
    ): AwsCredentialsProvider =
        provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }
}
