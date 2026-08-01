package io.bluetape4k.aws.bedrock

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder
import java.net.URI

/**
 * Bedrock 엔드포인트가 HTTPS 또는 리터럴 루프백 HTTP를 사용하는지 검증합니다.
 *
 * 호스트 이름은 확인하지 않습니다. 일반 HTTP는 로컬 에뮬레이터 테스트로 제한됩니다.
 */
@PublishedApi
internal fun URI.requireTrustedBedrockEndpoint(): URI = apply {
    val normalizedHost = host
        ?.lowercase()
        ?.removePrefix("[")
        ?.removeSuffix("]")
    require(!normalizedHost.isNullOrBlank()) {
        "Bedrock endpoint must include a host."
    }
    val isLoopback = normalizedHost == "localhost" ||
        normalizedHost == "127.0.0.1" ||
        normalizedHost == "::1"
    require(
        scheme.equals("https", ignoreCase = true) ||
            (scheme.equals("http", ignoreCase = true) && isLoopback),
    ) {
        "Bedrock endpoint must use HTTPS; plain HTTP is allowed only for literal loopback tests."
    }
}

/**
 * AWS SDK v2 [BedrockRuntimeClient]를 생성합니다.
 *
 * 최종 엔드포인트는 리터럴 루프백 HTTP를 제외하면 HTTPS를 사용해야 합니다. 반환된 클라이언트는
 * 호출자가 소유하고 일찍 닫을 수 있으며, 수명 주기 안전망으로 [ShutdownQueue]에도 등록됩니다.
 * 애플리케이션은 런타임에 `software.amazon.awssdk:bedrockruntime`을 추가해야 합니다.
 */
inline fun bedrockRuntimeClient(
    builder: BedrockRuntimeClientBuilder.() -> Unit,
): BedrockRuntimeClient {
    val client = BedrockRuntimeClient.builder().apply(builder).build()
    try {
        client.serviceClientConfiguration()
            .endpointOverride()
            .orElse(null)
            ?.requireTrustedBedrockEndpoint()
    } catch (cause: Throwable) {
        try {
            client.close()
        } finally {
            throw cause
        }
    }
    return client.apply { ShutdownQueue.register(this) }
}

/**
 * 선택적인 AWS 설정으로 호출자가 소유하는 [BedrockRuntimeClient]를 생성합니다.
 *
 * 명시적 파라미터는 도우미가 소유하며 [builder]보다 우선합니다. 로컬 테스트에서 사용하는
 * 리터럴 루프백 HTTP를 제외하면 HTTPS가 필요합니다. 호출자는 클라이언트를 일찍 닫을 수 있으며,
 * 애플리케이션은 런타임에 `software.amazon.awssdk:bedrockruntime`을 추가해야 합니다.
 */
inline fun bedrockRuntimeClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: BedrockRuntimeClientBuilder.() -> Unit = {},
): BedrockRuntimeClient = bedrockRuntimeClient {
    builder()
    endpoint?.requireTrustedBedrockEndpoint()?.let(::endpointOverride)
    region?.let(::region)
    credentialsProvider?.let(::credentialsProvider)
    httpClient(httpClient)
}
