package io.bluetape4k.aws.s3tables

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3tables.S3TablesClient
import software.amazon.awssdk.services.s3tables.S3TablesClientBuilder
import java.net.URI

/**
 * AWS SDK v2 [S3TablesClient]를 생성하고 [ShutdownQueue]에 등록합니다.
 *
 * 애플리케이션 수명 동안 재사용할 client에 사용하세요. 반환된 client를 일찍 닫을 때는 호출자가
 * `close()`를 호출하며, 짧은 범위의 작업에는 [withS3TablesClient]를 사용합니다.
 *
 * @param builder [S3TablesClientBuilder]를 구성하는 블록
 * @return [ShutdownQueue]에 등록된 [S3TablesClient]
 */
inline fun s3TablesClient(builder: S3TablesClientBuilder.() -> Unit): S3TablesClient =
    S3TablesClient.builder().apply(builder).build().apply(ShutdownQueue::register)

/**
 * endpoint, region, credentials, HTTP client를 적용한 애플리케이션용 [S3TablesClient]를 생성합니다.
 *
 * 명시적 인자는 먼저 적용하고 [builder]를 마지막에 실행하므로 callback의 유효한 override가 최종
 * 설정이 됩니다. 반환된 client는 [ShutdownQueue]에 등록되고, 전달한 HTTP client의 수명은 호출자가
 * 소유합니다.
 *
 * @param endpoint S3 Tables 서비스 endpoint override입니다.
 * @param region AWS 리전입니다.
 * @param credentialsProvider AWS 자격 증명 공급자입니다.
 * @param httpClient 호출자가 소유하는 동기 HTTP client입니다.
 * @param builder [S3TablesClientBuilder]를 추가로 구성하는 블록
 * @return [ShutdownQueue]에 등록된 [S3TablesClient]
 */
inline fun s3TablesClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    noinline builder: S3TablesClientBuilder.() -> Unit = {},
): S3TablesClient = buildS3TablesClient(endpoint, region, credentialsProvider, httpClient, builder)
    .apply(ShutdownQueue::register)

/**
 * 짧은 범위에서 사용할 미등록 [S3TablesClient]를 생성하고 block 종료 시 service client를 닫습니다.
 *
 * 성공, 예외, cancellation 모두에서 service client를 닫지만 전달한 외부 HTTP client는 닫지 않습니다.
 * 애플리케이션 수명 동안 공유할 client는 [s3TablesClient] 또는 [s3TablesClientOf]를 사용하세요.
 *
 * @param endpoint S3 Tables 서비스 endpoint override입니다.
 * @param region AWS 리전입니다.
 * @param credentialsProvider AWS 자격 증명 공급자입니다.
 * @param httpClient 호출자가 소유하는 동기 HTTP client입니다.
 * @param builder [S3TablesClientBuilder]를 추가로 구성하는 블록
 * @param block 생성된 client로 실행할 블록
 */
inline fun <R> withS3TablesClient(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    noinline builder: S3TablesClientBuilder.() -> Unit = {},
    block: (S3TablesClient) -> R,
): R {
    val client = buildS3TablesClient(endpoint, region, credentialsProvider, httpClient, builder)
    return try {
        block(client)
    } finally {
        client.close()
    }
}

internal inline fun <R> withS3TablesClient(
    clientFactory: () -> S3TablesClient,
    block: (S3TablesClient) -> R,
): R {
    val client = clientFactory()
    return try {
        block(client)
    } finally {
        client.close()
    }
}

@PublishedApi
internal fun buildS3TablesClient(
    endpoint: URI?,
    region: Region?,
    credentialsProvider: AwsCredentialsProvider?,
    httpClient: SdkHttpClient,
    builder: S3TablesClientBuilder.() -> Unit,
): S3TablesClient = S3TablesClient.builder().apply {
    endpoint?.let(::endpointOverride)
    region?.let(::region)
    credentialsProvider?.let(::credentialsProvider)
    httpClient(httpClient)
    builder()
}.build()
