package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsAsyncClientCustomizer
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.applyGlobalCustomizers
import io.bluetape4k.aws.spring.applyServiceCustomizers
import io.bluetape4k.aws.spring.resolveClientDefaults
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * DynamoDB용 Spring Boot 4 자동 구성입니다.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnAwsEnabled
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
        awsProperties: ObjectProvider<AwsProperties>,
        properties: DynamoDbProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        httpClient: ObjectProvider<SdkAsyncHttpClient>,
        globalCustomizers: ObjectProvider<AwsAsyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<DynamoDbAsyncClientBuilder>>,
    ): DynamoDbAsyncClient =
        DynamoDbAsyncClient.builder()
            .credentialsProvider(resolveDynamoDbCredentialsProvider(credentialsProvider))
            .applyAwsDefaults(
                resolveDynamoDbAwsProperties(awsProperties).resolveClientDefaults(
                    properties.region,
                    properties.endpointOverride,
                )
            )
            .apply {
                httpClient.getIfAvailable()?.let { httpClient(it) }
            }
            .also { it.applyGlobalCustomizers("dynamodb", globalCustomizers) }
            .applyServiceCustomizers(serviceCustomizers)
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
}
