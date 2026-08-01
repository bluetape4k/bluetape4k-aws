package io.bluetape4k.aws.kotlin.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * [S3Client]를 생성합니다.
 *
 * ```kotlin
 * val s3Client = s3ClientOf(
 *    endpointUrl = Url.parse("http://localhost:4566"),
 *    region = "us-west-2",
 *    credentialsProvider = StaticCredentialsProvider { accessKeyId = "test"; secretAccessKey = "test" }
 * ) {
 *    clientName = "bluetape4k-s3-client"
 * }
 * ```
 *
 * @param endpointUrl S3 엔드포인트 URL
 * @param region AWS 리전
 * @param credentialsProvider AWS 자격 증명 공급자
 * @param httpClient 외부에서 관리하는 HTTP 엔진입니다. 생략하면 SDK가 엔진 소유권을 관리합니다.
 * @param builder [S3Client.Config.Builder]를 통해 [S3Client.Config]를 구성하는 블록
 * @return 구성된 [S3Client]
 */
inline fun s3ClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: S3Client.Config.Builder.() -> Unit = {},
): S3Client =
    S3Client {
        endpointUrl?.let { this.endpointUrl = it }
        region?.let { this.region = it }
        credentialsProvider?.let { this.credentialsProvider = it }
        httpClient?.let { this.httpClient = it }

        builder()
    }

/**
 * [S3Client]를 생성하고 [block]을 실행한 뒤 client를 자동으로 닫습니다.
 *
 * SDK가 내부 HTTP 엔진을 직접 관리하므로 close() 시 엔진도 함께 종료됩니다.
 *
 * ```kotlin
 * withS3Client(endpointUrl, region, credentialsProvider) { client ->
 *     client.putObject { ... }
 * }
 * ```
 *
 * @param block client를 사용하는 suspend 블록입니다. AWS SDK 작업은 suspend 함수입니다.
 */
suspend fun <R> withS3Client(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (S3Client) -> R,
): R = withS3Client(
    clientFactory = { s3ClientOf(endpointUrl, region, credentialsProvider) },
    block = block,
)

/**
 * [clientFactory]가 생성한 client로 블록을 실행하고 정상 반환, 실패, coroutine 취소 시 client를 닫습니다.
 *
 * 이 내부 seam은 public helper가 동일한 소유권 경로를 사용하도록 유지하면서
 * 네트워크 I/O 없이 client 수명 주기를 결정적으로 회귀 테스트할 수 있게 합니다.
 */
internal suspend fun <R> withS3Client(
    clientFactory: () -> S3Client,
    block: suspend (S3Client) -> R,
): R = clientFactory().useSafe { client ->
    block(client)
}
