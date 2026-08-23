package io.bluetape4k.aws.lambda

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.LambdaClientBuilder
import java.net.URI

/** AWS SDK v2 [LambdaClient]를 만들고 애플리케이션 종료 큐에 등록합니다. */
inline fun lambdaClient(builder: LambdaClientBuilder.() -> Unit): LambdaClient =
    LambdaClient.builder()
        .apply(builder)
        .build()
        .apply { ShutdownQueue.register(this) }

/**
 * endpoint, region, credentials, HTTP client를 적용한 애플리케이션용 [LambdaClient]를 생성합니다.
 * 명시적 인자는 먼저 적용하고 callback을 마지막에 적용합니다.
 */
inline fun lambdaClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    noinline builder: LambdaClientBuilder.() -> Unit = {},
): LambdaClient = buildLambdaClient(endpoint, region, credentialsProvider, httpClient, builder)
    .apply { ShutdownQueue.register(this) }

/**
 * 짧은 범위에서 사용할 미등록 [LambdaClient]를 생성하고 block 종료 시 service client만 닫습니다.
 * 전달한 HTTP client의 소유권은 호출자에게 있습니다.
 */
inline fun <R> withLambdaClient(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    noinline builder: LambdaClientBuilder.() -> Unit = {},
    block: (LambdaClient) -> R,
): R {
    val client = buildLambdaClient(endpoint, region, credentialsProvider, httpClient, builder)
    return try {
        block(client)
    } finally {
        client.close()
    }
}

@PublishedApi
internal fun buildLambdaClient(
    endpoint: URI?,
    region: Region?,
    credentialsProvider: AwsCredentialsProvider?,
    httpClient: SdkHttpClient,
    builder: LambdaClientBuilder.() -> Unit,
): LambdaClient = LambdaClient.builder()
    .apply {
        endpoint?.let { endpointOverride(it) }
        region?.let { region(it) }
        credentialsProvider?.let { credentialsProvider(it) }
        httpClient(httpClient)
        builder()
    }
    .build()
