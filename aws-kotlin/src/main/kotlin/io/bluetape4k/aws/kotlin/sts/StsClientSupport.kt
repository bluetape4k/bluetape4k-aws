package io.bluetape4k.aws.kotlin.sts

import aws.sdk.kotlin.services.sts.StsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * AWS Kotlin SDK [StsClient]를 생성한다.
 *
 * AWS Security Token Service (STS)는 application이 AWS resource 접근에 사용할 수
 * 있는 임시 limited-privilege credential을 발급한다.
 *
 * 예시:
 * ```kotlin
 * val client = stsClientOf(
 *     endpointUrl = Url.parse("http://localhost:4566"),
 *     region = "us-east-1",
 *     credentialsProvider = myCredentialsProvider
 * )
 * ```
 *
 * @param endpointUrl STS service endpoint URL. null이면 SDK가 default AWS endpoint를 사용한다.
 * @param region AWS region. null이면 SDK가 environment chain에서 region을 resolve한다.
 * @param credentialsProvider AWS credential provider. null이면 SDK가 default credentials chain을 사용한다.
 * @param httpClient 외부에서 관리하는 optional HTTP engine. 생략하면 SDK가 engine ownership을 관리한다.
 * @param builder [StsClient.Config.Builder]에 적용할 추가 설정이다.
 * @return 설정이 적용된 [StsClient] instance.
 */
inline fun stsClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: StsClient.Config.Builder.() -> Unit = {},
): StsClient = StsClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }

    builder()
}

/**
 * [StsClient]를 생성하고 [block]을 실행한 뒤 client를 자동으로 닫는다.
 *
 * SDK가 HTTP engine을 소유하는 경우 client를 닫으면 engine도 함께 닫힌다.
 *
 * ```kotlin
 * withStsClient(endpointUrl, region, credentialsProvider) { client ->
 *     client.getCallerIdentity()
 * }
 * ```
 *
 * @param block 설정된 [StsClient]를 전달받는 suspend block이다.
 */
suspend fun <R> withStsClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (StsClient) -> R,
): R = stsClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
