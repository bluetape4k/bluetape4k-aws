package io.bluetape4k.aws.dynamodb

import io.bluetape4k.aws.http.SdkHttpClientProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClientBuilder
import java.net.URI

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val dynamoDbStreamsClient = DynamoDbStreamsClient {
 *     credentialsProvider(credentialsProvider)
 *     endpointOverride(endpoint)
 *     region(region)
 *     httpClient(SdkHttpClientProvider.Apache.apacheHttpClient)
 * }
 * ```
 *
 * @return Return value.
 */
inline fun dynamoDbStreamsClient(
    builder: DynamoDbStreamsClientBuilder.() -> Unit,
): DynamoDbStreamsClient {
    return DynamoDbStreamsClient.builder().apply(builder).build()
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val dynamoDbStreamsClient = dynamoDbStreamsClientOf(
 *    endpoint = endpoint,
 *    region = region,
 *    credentialsProvider = credentialsProvider,
 * ) {
 *    httpClient(SdkHttpClientProvider.Apache.apacheHttpClient)
 * }
 * ```
 *
 * @return Return value.
 * @see [DynamoDbStreamsClient]
 */
inline fun dynamoDbStreamsClientOf(
    endpoint: URI,
    region: Region,
    credentialsProvider: AwsCredentialsProvider,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: DynamoDbStreamsClientBuilder.() -> Unit = {},
): DynamoDbStreamsClient = dynamoDbStreamsClient {
    endpointOverride(endpoint)
    region(region)
    credentialsProvider(credentialsProvider)
    httpClient(httpClient)

    builder()
}
