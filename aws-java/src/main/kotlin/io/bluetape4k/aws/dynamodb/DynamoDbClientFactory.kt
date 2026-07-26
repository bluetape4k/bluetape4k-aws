package io.bluetape4k.aws.dynamodb

import io.bluetape4k.aws.dynamodb.enhanced.dynamoDbEnhancedAsyncClient
import io.bluetape4k.aws.dynamodb.enhanced.dynamoDbEnhancedAsyncClientOf
import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.http.SdkHttpClientProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder
import java.net.URI

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 */
object DynamoDbClientFactory {

    /**
     * See the API documentation for details.
     */
    object Sync {

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val client = DynamoDbClientFactory.Sync.create {
         *     region(Region.AP_NORTHEAST_2)
         * }
         *
         * check(client.serviceName() == "DynamoDb")
         * ```
         */
        inline fun create(
            builder: DynamoDbClientBuilder.() -> Unit,
        ): DynamoDbClient =
            dynamoDbClient(builder)

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val client = DynamoDbClientFactory.Sync.create(
         *     region = Region.AP_NORTHEAST_2,
         * )
         *
         * check(client != null)
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
            builder: DynamoDbClientBuilder.() -> Unit = {},
        ): DynamoDbClient =
            dynamoDbClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }

    /**
     * See the API documentation for details.
     */
    object Async {

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val client = DynamoDbClientFactory.Async.create {
         *     region(Region.AP_NORTHEAST_2)
         * }
         *
         * check(client.serviceName() == "DynamoDb")
         * ```
         */
        inline fun create(
            builder: DynamoDbAsyncClientBuilder.() -> Unit,
        ): DynamoDbAsyncClient =
            dynamoDbAsyncClient(builder)

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val client = DynamoDbClientFactory.Async.create(
         *     region = Region.AP_NORTHEAST_2,
         * )
         *
         * check(client != null)
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
            builder: DynamoDbAsyncClientBuilder.() -> Unit = {},
        ): DynamoDbAsyncClient =
            dynamoDbAsyncClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }

    /**
     * See the API documentation for details.
     */
    object EnhancedAsync {

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val enhanced = DynamoDbClientFactory.EnhancedAsync.create {
         *     dynamoDbClient(DynamoDbAsyncClient.create())
         * }
         *
         * check(enhanced != null)
         * ```
         */
        inline fun create(
            builder: DynamoDbEnhancedAsyncClient.Builder.() -> Unit,
        ): DynamoDbEnhancedAsyncClient =
            dynamoDbEnhancedAsyncClient(builder)

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val asyncClient = DynamoDbClientFactory.Async.create { region(Region.AP_NORTHEAST_2) }
         * val enhanced = DynamoDbClientFactory.EnhancedAsync.create(asyncClient)
         *
         * check(enhanced != null)
         * ```
         */
        inline fun create(
            asyncClient: DynamoDbAsyncClient,
            builder: DynamoDbEnhancedAsyncClient.Builder.() -> Unit = {},
        ): DynamoDbEnhancedAsyncClient {
            return dynamoDbEnhancedAsyncClientOf(asyncClient, builder)
        }

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val enhanced = DynamoDbClientFactory.EnhancedAsync.create(
         *     region = Region.AP_NORTHEAST_2,
         * )
         *
         * check(enhanced != null)
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
            builder: DynamoDbEnhancedAsyncClient.Builder.() -> Unit = {},
        ): DynamoDbEnhancedAsyncClient {
            val asyncClient = Async.create(endpointOverride, region, credentialsProvider, httpClient)
            return dynamoDbEnhancedAsyncClientOf(asyncClient, builder)
        }
    }
}
