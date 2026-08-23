package io.bluetape4k.aws.kotlin.s3tables

import aws.sdk.kotlin.services.s3tables.S3TablesClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * AWS Kotlin SDK [S3TablesClient]를 생성합니다.
 *
 * 반환된 client는 호출자가 소유하므로 애플리케이션 수명 주기에 맞춰 `close()`를 호출해야 합니다.
 * [httpClient]를 주입한 경우 HTTP engine의 수명도 호출자가 관리합니다.
 *
 * @param endpointUrl S3 Tables 서비스 endpoint URL입니다. `null`이면 기본 endpoint를 사용합니다.
 * @param region AWS 리전입니다. `null`이면 SDK 기본 설정을 사용합니다.
 * @param credentialsProvider AWS 자격 증명 공급자입니다. `null`이면 기본 자격 증명 체인을 사용합니다.
 * @param httpClient 호출자가 소유하는 HTTP engine입니다. 생략하면 SDK가 engine을 생성합니다.
 * @param builder [S3TablesClient.Config.Builder]를 추가로 구성하는 블록
 * @return 구성된 [S3TablesClient]
 */
inline fun s3TablesClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: S3TablesClient.Config.Builder.() -> Unit = {},
): S3TablesClient = S3TablesClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }
    builder()
}

/**
 * [S3TablesClient]를 생성하고 [block]을 실행한 뒤 service client를 자동으로 닫습니다.
 *
 * 성공, 예외, coroutine cancellation 모두에서 service client를 닫습니다. [httpClient]를 주입했다면
 * 해당 HTTP engine은 호출자가 소유하며 이 helper가 닫지 않습니다.
 *
 * @param endpointUrl S3 Tables 서비스 endpoint URL입니다. `null`이면 기본 endpoint를 사용합니다.
 * @param region AWS 리전입니다. `null`이면 SDK 기본 설정을 사용합니다.
 * @param credentialsProvider AWS 자격 증명 공급자입니다. `null`이면 기본 자격 증명 체인을 사용합니다.
 * @param httpClient 호출자가 소유하는 HTTP engine입니다. 생략하면 SDK가 engine을 생성합니다.
 * @param builder [S3TablesClient.Config.Builder]를 추가로 구성하는 블록
 * @param block client를 사용하는 suspend 블록입니다.
 */
suspend fun <R> withS3TablesClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    builder: S3TablesClient.Config.Builder.() -> Unit = {},
    block: suspend (S3TablesClient) -> R,
): R = withS3TablesClient(
    clientFactory = { s3TablesClientOf(endpointUrl, region, credentialsProvider, httpClient, builder) },
    block = block,
)

internal suspend fun <R> withS3TablesClient(
    clientFactory: () -> S3TablesClient,
    block: suspend (S3TablesClient) -> R,
): R = clientFactory().useSafe { client -> block(client) }
