package io.bluetape4k.aws.dynamodb

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder
import java.net.URI

/**
 * See the API documentation for details.
 * See the API documentation for details.
 *
 * ```kotlin
 * val dynamoDbAsyncClient = DynamoDbAsyncClient {
 *      credentialsProvider(credentialsProvider)
 *      endpointOverride(endpoint)
 *      region(region)
 *      httpClient(SdkAsyncHttpClientProvider.Netty.nettyNioAsyncHttpClient)
 * }
 * ```
 * @param builder Parameter.
 * @return Return value.
 */
inline fun dynamoDbAsyncClient(
    builder: DynamoDbAsyncClientBuilder.() -> Unit,
): DynamoDbAsyncClient =
    DynamoDbAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val dynamoDbAsyncClient = dynamoDbAsyncClientOf(
 *     endpoint = endpoint,
 *     region = region,
 *     credentialsProvider = credentialsProvider,
 * ) {
 *   httpClient(SdkAsyncHttpClientProvider.Netty.nettyNioAsyncHttpClient)
 * }
 * ```
 * @param endpoint Parameter.
 * @param region Parameter.
 * @param credentialsProvider Parameter.
 * @param builder Parameter.
 *
 * @return Return value.
 * @see DynamoDbAsyncClient
 */
inline fun dynamoDbAsyncClientOf(
    endpointOverride: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: DynamoDbAsyncClientBuilder.() -> Unit = {},
): DynamoDbAsyncClient = dynamoDbAsyncClient {
    endpointOverride?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
