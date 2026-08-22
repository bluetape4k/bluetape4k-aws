package io.bluetape4k.aws.kotlin.sfn

import aws.sdk.kotlin.services.sfn.SfnClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/** AWS SDK for Kotlin Step Functions client를 생성합니다. */
inline fun sfnClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: SfnClient.Config.Builder.() -> Unit = {},
): SfnClient = SfnClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }

    builder()
}

/**
 * Step Functions client를 생성해 [block]을 실행한 뒤 service client를 닫습니다.
 *
 * [httpClient]로 전달한 HTTP 엔진은 호출자가 소유하므로 이 함수가 닫지 않습니다.
 */
suspend fun <R> withSfnClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    builder: SfnClient.Config.Builder.() -> Unit = {},
    block: suspend (SfnClient) -> R,
): R = withSfnClient(
    clientFactory = { sfnClientOf(endpointUrl, region, credentialsProvider, httpClient, builder) },
    block = block,
)

/** client factory를 주입해 lifecycle을 네트워크 I/O 없이 검증하는 내부 seam입니다. */
internal suspend fun <R> withSfnClient(
    clientFactory: () -> SfnClient,
    block: suspend (SfnClient) -> R,
): R = clientFactory().useSafe { client ->
    block(client)
}
