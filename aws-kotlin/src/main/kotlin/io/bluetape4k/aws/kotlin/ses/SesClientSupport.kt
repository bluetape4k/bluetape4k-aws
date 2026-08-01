package io.bluetape4k.aws.kotlin.ses

import aws.sdk.kotlin.services.ses.SesClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * AWS Kotlin SDK [SesClient] 인스턴스를 생성합니다.
 *
 * ```kotlin
 * val client = sesClientOf(
 *     endpointUrl = Url.parse("http://localhost:4566"),
 *     region = "us-east-1",
 *     credentialsProvider = credentialsProvider
 * )
 * ```
 *
 * @param endpointUrl SES 서비스 엔드포인트 URL입니다. `null`이면 기본 AWS 엔드포인트를 사용합니다.
 * @param region AWS 리전입니다. `null`이면 환경에서 확인합니다.
 * @param credentialsProvider AWS 자격 증명 공급자입니다. `null`이면 기본 자격 증명 체인을 사용합니다.
 * @param httpClient 외부에서 관리하는 HTTP 엔진입니다. 생략하면 SDK가 엔진 소유권을 관리합니다.
 * @param builder [SesClient.Config.Builder]를 추가로 구성하는 블록
 * @return 구성된 [SesClient] 인스턴스
 */
inline fun sesClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: SesClient.Config.Builder.() -> Unit = {},
): SesClient = SesClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }

    builder()
}

/**
 * [SesClient]를 생성하고 [block]을 실행한 뒤 client를 자동으로 닫습니다.
 *
 * SDK가 내부 HTTP 엔진을 직접 관리하므로 close() 시 엔진도 함께 종료됩니다.
 *
 * ```kotlin
 * withSesClient(endpointUrl, region, credentialsProvider) { client ->
 *     client.sendEmail { ... }
 * }
 * ```
 *
 * @param block client로 실행할 suspend 블록입니다. AWS SDK 작업이 suspend 함수이므로 이 블록도 suspend입니다.
 */
suspend fun <R> withSesClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (SesClient) -> R,
): R = withSesClient(
    clientFactory = { sesClientOf(endpointUrl, region, credentialsProvider) },
    block = block,
)

/**
 * [clientFactory]가 생성한 client로 블록을 실행하고 정상 반환, 실패, coroutine 취소 시 client를 닫습니다.
 *
 * 이 내부 seam은 public helper가 동일한 소유권 경로를 사용하도록 유지하면서
 * 네트워크 I/O 없이 client 수명 주기를 결정적으로 회귀 테스트할 수 있게 합니다.
 */
internal suspend fun <R> withSesClient(
    clientFactory: () -> SesClient,
    block: suspend (SesClient) -> R,
): R = clientFactory().useSafe { client ->
    block(client)
}
