package io.bluetape4k.aws.lambda

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.LambdaAsyncClientBuilder
import java.net.URI

/** AWS SDK v2 [LambdaAsyncClient]를 만들고 애플리케이션 종료 큐에 등록합니다. */
inline fun lambdaAsyncClient(builder: LambdaAsyncClientBuilder.() -> Unit): LambdaAsyncClient =
    LambdaAsyncClient.builder()
        .apply(builder)
        .build()
        .apply { ShutdownQueue.register(this) }

/**
 * endpoint, region, credentials, async HTTP client를 적용한 [LambdaAsyncClient]를 생성합니다.
 * 명시적 인자는 먼저 적용하고 callback을 마지막에 적용합니다.
 */
inline fun lambdaAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    noinline builder: LambdaAsyncClientBuilder.() -> Unit = {},
): LambdaAsyncClient = buildLambdaAsyncClient(endpoint, region, credentialsProvider, httpClient, builder)
    .apply { ShutdownQueue.register(this) }

/**
 * 짧은 범위에서 사용할 미등록 [LambdaAsyncClient]를 생성하고 block 종료 시 service client만 닫습니다.
 * 전달한 async HTTP client의 소유권은 호출자에게 있습니다.
 */
suspend inline fun <R> withLambdaAsyncClient(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    noinline builder: LambdaAsyncClientBuilder.() -> Unit = {},
    crossinline block: suspend (LambdaAsyncClient) -> R,
): R {
    val client = buildLambdaAsyncClient(endpoint, region, credentialsProvider, httpClient, builder)
    return try {
        block(client)
    } finally {
        client.close()
    }
}

@PublishedApi
internal fun buildLambdaAsyncClient(
    endpoint: URI?,
    region: Region?,
    credentialsProvider: AwsCredentialsProvider?,
    httpClient: SdkAsyncHttpClient,
    builder: LambdaAsyncClientBuilder.() -> Unit,
): LambdaAsyncClient = LambdaAsyncClient.builder()
    .apply {
        endpoint?.let { endpointOverride(it) }
        region?.let { region(it) }
        credentialsProvider?.let { credentialsProvider(it) }
        httpClient(httpClient)
        builder()
    }
    .build()
