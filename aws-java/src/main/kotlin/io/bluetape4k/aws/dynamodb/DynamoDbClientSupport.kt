package io.bluetape4k.aws.dynamodb

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder
import java.net.URI

/**
 * See the API documentation for details.
 * See the API documentation for details.
 *
 * ```kotlin
 * val dynamoDbClient = DynamoDbClient {
 *     credentialsProvider(credentialsProvider)
 *     endpointOverride(endpoint)
 *     region(region)
 *     httpClient(SdkHttpClientProvider.Apache.apacheHttpClient)
 * }
 * ```
 *
 * @return Return value.
 */
inline fun dynamoDbClient(
    builder: DynamoDbClientBuilder.() -> Unit,
): DynamoDbClient {
    return DynamoDbClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val dynamoDbClient = dynamoDbClientOf(
 *    endpoint = endpoint,
 *    region = region,
 *    credentialsProvider = credentialsProvider,
 * ) {
 *    httpClient(SdkHttpClientProvider.Apache.apacheHttpClient)
 * }
 * ```
 *
 * @return Return value.
 * @see [DynamoDbClient]
 */
inline fun dynamoDbClientOf(
    endpointOverride: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: DynamoDbClientBuilder.() -> Unit = {},
): DynamoDbClient = dynamoDbClient {
    endpointOverride?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
