package io.bluetape4k.aws.dynamodbstreams

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClientBuilder
import java.net.URI

/** caller가 소유할 Java SDK DynamoDB Streams async client를 생성합니다. */
inline fun dynamoDbStreamsAsyncClientOf(
    endpoint: URI,
    region: Region,
    credentialsProvider: AwsCredentialsProvider,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    noinline builder: DynamoDbStreamsAsyncClientBuilder.() -> Unit = {},
): DynamoDbStreamsAsyncClient = buildDynamoDbStreamsAsyncClient(
    endpoint = endpoint,
    region = region,
    credentialsProvider = credentialsProvider,
    httpClient = httpClient,
    builder = builder,
).apply(ShutdownQueue::register)

/** client를 생성해 block을 실행한 뒤 성공·실패와 무관하게 닫습니다. */
suspend fun <R> withDynamoDbStreamsAsyncClient(
    endpoint: URI,
    region: Region,
    credentialsProvider: AwsCredentialsProvider,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: DynamoDbStreamsAsyncClientBuilder.() -> Unit = {},
    block: suspend (DynamoDbStreamsAsyncClient) -> R,
): R {
    val client = buildDynamoDbStreamsAsyncClient(endpoint, region, credentialsProvider, httpClient, builder)
    return try {
        block(client)
    } finally {
        client.close()
    }
}

/** 테스트와 짧은 범위 실행에 사용할 미등록 client factory overload입니다. */
internal suspend fun <R> withDynamoDbStreamsAsyncClient(
    clientFactory: () -> DynamoDbStreamsAsyncClient,
    block: suspend (DynamoDbStreamsAsyncClient) -> R,
): R {
    val client = clientFactory()
    return try {
        block(client)
    } finally {
        client.close()
    }
}

@PublishedApi
internal fun buildDynamoDbStreamsAsyncClient(
    endpoint: URI,
    region: Region,
    credentialsProvider: AwsCredentialsProvider,
    httpClient: SdkAsyncHttpClient,
    builder: DynamoDbStreamsAsyncClientBuilder.() -> Unit,
): DynamoDbStreamsAsyncClient = DynamoDbStreamsAsyncClient.builder().apply {
    endpointOverride(endpoint)
    region(region)
    credentialsProvider(credentialsProvider)
    httpClient(httpClient)
    builder()
}.build()
