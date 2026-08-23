package io.bluetape4k.aws.sfn

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.SfnAsyncClientBuilder
import java.net.URI

/**
 * AWS SDK v2 [SfnAsyncClient]를 생성하고 [ShutdownQueue]에 등록합니다.
 *
 * 애플리케이션 수명 동안 재사용할 client에 사용하세요. 반환된 client의 조기 종료는 호출자가
 * `close()`로 관리하며, 짧은 범위의 사용에는 [withSfnAsyncClient]를 사용합니다.
 */
inline fun sfnAsyncClient(builder: SfnAsyncClientBuilder.() -> Unit): SfnAsyncClient =
    SfnAsyncClient.builder()
        .apply(builder)
        .build()
        .apply { ShutdownQueue.register(this) }

/**
 * endpoint, region, credentials, HTTP client를 적용한 애플리케이션용 [SfnAsyncClient]를 생성합니다.
 *
 * 명시적 인자는 먼저 적용하고 [builder]를 마지막에 실행하므로 callback의 유효한 override가 최종
 * 설정이 됩니다. 전달한 HTTP client의 수명은 호출자가 소유합니다.
 */
inline fun sfnAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    noinline builder: SfnAsyncClientBuilder.() -> Unit = {},
): SfnAsyncClient = buildSfnAsyncClient(endpoint, region, credentialsProvider, httpClient, builder)
    .apply { ShutdownQueue.register(this) }

/**
 * 짧은 범위에서 사용할 미등록 [SfnAsyncClient]를 생성하고 block 종료 시 service client를 닫습니다.
 *
 * 성공, 예외, cancellation 모두에서 service client를 닫지만 외부 소유 HTTP client는 닫지 않습니다.
 */
suspend inline fun <R> withSfnAsyncClient(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    noinline builder: SfnAsyncClientBuilder.() -> Unit = {},
    crossinline block: suspend (SfnAsyncClient) -> R,
): R {
    val client = buildSfnAsyncClient(endpoint, region, credentialsProvider, httpClient, builder)
    return try {
        block(client)
    } finally {
        client.close()
    }
}

@PublishedApi
internal fun buildSfnAsyncClient(
    endpoint: URI?,
    region: Region?,
    credentialsProvider: AwsCredentialsProvider?,
    httpClient: SdkAsyncHttpClient,
    builder: SfnAsyncClientBuilder.() -> Unit,
): SfnAsyncClient = SfnAsyncClient.builder()
    .apply {
        endpoint?.let { endpointOverride(it) }
        region?.let { region(it) }
        credentialsProvider?.let { credentialsProvider(it) }
        httpClient(httpClient)
        builder()
    }
    .build()
