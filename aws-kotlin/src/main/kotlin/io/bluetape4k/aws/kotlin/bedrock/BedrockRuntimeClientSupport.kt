package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * Bedrock 엔드포인트가 HTTPS 또는 리터럴 루프백 HTTP를 사용하는지 검증합니다.
 *
 * 호스트 이름은 확인하지 않습니다. 일반 HTTP는 로컬 에뮬레이터 테스트로 제한됩니다.
 */
@PublishedApi
internal fun Url.requireTrustedBedrockEndpoint(): Url = apply {
    val protocol = scheme.protocolName.lowercase()
    val normalizedHost = host.toString()
        .lowercase()
        .removePrefix("[")
        .removeSuffix("]")
    require(normalizedHost.isNotBlank()) {
        "Bedrock endpoint must include a host."
    }
    val isLoopback = normalizedHost == "localhost" ||
        normalizedHost == "127.0.0.1" ||
        normalizedHost == "::1"
    require(protocol == "https" || (protocol == "http" && isLoopback)) {
        "Bedrock endpoint must use HTTPS; plain HTTP is allowed only for literal loopback tests."
    }
}

/**
 * 생성된 클라이언트를 검증하고 검증에 실패하면 정확히 한 번 닫습니다.
 */
@PublishedApi
internal fun BedrockRuntimeClient.requireTrustedBedrockConfiguration(): BedrockRuntimeClient {
    try {
        config.endpointUrl?.requireTrustedBedrockEndpoint()
    } catch (cause: Throwable) {
        try {
            close()
        } finally {
            throw cause
        }
    }
    return this
}

/**
 * 호출자가 소유하는 AWS Kotlin SDK [BedrockRuntimeClient]를 생성합니다.
 *
 * 명시적 파라미터는 도우미가 소유하며 [builder]보다 우선합니다. 최종 엔드포인트는 리터럴
 * 루프백 HTTP를 제외하면 HTTPS를 사용해야 합니다. 애플리케이션은 런타임에
 * `aws.sdk.kotlin:bedrockruntime`을 추가하고 반환된 클라이언트를 닫아야 합니다.
 */
inline fun bedrockRuntimeClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: BedrockRuntimeClient.Config.Builder.() -> Unit = {},
): BedrockRuntimeClient =
    BedrockRuntimeClient {
        builder()
        endpointUrl?.requireTrustedBedrockEndpoint()?.let { this.endpointUrl = it }
        region?.let { this.region = it }
        credentialsProvider?.let { this.credentialsProvider = it }
        httpClient?.let { this.httpClient = it }
    }.requireTrustedBedrockConfiguration()

/**
 * 블록이 소유하는 [BedrockRuntimeClient]를 생성하고 [block]을 실행한 뒤 닫습니다.
 *
 * 모든 콜드 Flow 수집은 [block] 안에서 완료하세요. 이 범위를 벗어난 Flow는 범위가 닫힌 뒤
 * 클라이언트를 사용할 수 없습니다. 애플리케이션은 런타임에 `aws.sdk.kotlin:bedrockruntime`을 추가해야 합니다.
 */
suspend fun <R> withBedrockRuntimeClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    builder: BedrockRuntimeClient.Config.Builder.() -> Unit = {},
    block: suspend (BedrockRuntimeClient) -> R,
): R = withBedrockRuntimeClient(
    clientFactory = {
        bedrockRuntimeClientOf(
            endpointUrl = endpointUrl,
            region = region,
            credentialsProvider = credentialsProvider,
            httpClient = httpClient,
            builder = builder,
        )
    },
    block = block,
)

internal suspend inline fun <R> withBedrockRuntimeClient(
    clientFactory: () -> BedrockRuntimeClient,
    block: suspend (BedrockRuntimeClient) -> R,
): R = clientFactory().useSafe { client ->
    block(client)
}
