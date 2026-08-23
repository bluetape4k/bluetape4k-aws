package io.bluetape4k.aws.kotlin.lambda

import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/** AWS SDK for Kotlin Lambda client를 생성합니다. */
inline fun lambdaClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: LambdaClient.Config.Builder.() -> Unit = {},
): LambdaClient = LambdaClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }
    builder()
}

/** Lambda client를 생성해 block을 실행한 뒤 service client만 닫습니다. */
suspend fun <R> withLambdaClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    builder: LambdaClient.Config.Builder.() -> Unit = {},
    block: suspend (LambdaClient) -> R,
): R = withLambdaClient(
    clientFactory = { lambdaClientOf(endpointUrl, region, credentialsProvider, httpClient, builder) },
    block = block,
)

/** client factory를 주입해 lifecycle을 네트워크 I/O 없이 검증하는 내부 seam입니다. */
internal suspend fun <R> withLambdaClient(
    clientFactory: () -> LambdaClient,
    block: suspend (LambdaClient) -> R,
): R = clientFactory().useSafe { client ->
    block(client)
}
