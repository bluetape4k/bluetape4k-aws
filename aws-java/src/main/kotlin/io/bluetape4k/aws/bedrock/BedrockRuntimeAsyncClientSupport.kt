package io.bluetape4k.aws.bedrock

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClientBuilder
import java.net.URI

/**
 * AWS SDK v2 [BedrockRuntimeAsyncClient]를 생성합니다.
 *
 * 최종 엔드포인트는 리터럴 루프백 HTTP를 제외하면 HTTPS를 사용해야 합니다. 반환된 클라이언트는
 * 호출자가 소유하고 일찍 닫을 수 있으며, 수명 주기 안전망으로 [ShutdownQueue]에도 등록됩니다.
 * 애플리케이션은 런타임에 `software.amazon.awssdk:bedrockruntime`을 추가해야 합니다.
 */
inline fun bedrockRuntimeAsyncClient(
    builder: BedrockRuntimeAsyncClientBuilder.() -> Unit,
): BedrockRuntimeAsyncClient {
    val client = BedrockRuntimeAsyncClient.builder().apply(builder).build()
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
 * 선택적인 AWS 설정으로 호출자가 소유하는 [BedrockRuntimeAsyncClient]를 생성합니다.
 *
 * 명시적 파라미터는 도우미가 소유하며 [builder]보다 우선합니다. 로컬 테스트에서 사용하는
 * 리터럴 루프백 HTTP를 제외하면 HTTPS가 필요합니다. 호출자는 클라이언트를 일찍 닫을 수 있으며,
 * 애플리케이션은 런타임에 `software.amazon.awssdk:bedrockruntime`을 추가해야 합니다.
 */
inline fun bedrockRuntimeAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: BedrockRuntimeAsyncClientBuilder.() -> Unit = {},
): BedrockRuntimeAsyncClient = bedrockRuntimeAsyncClient {
    builder()
    endpoint?.requireTrustedBedrockEndpoint()?.let(::endpointOverride)
    region?.let(::region)
    credentialsProvider?.let(::credentialsProvider)
    httpClient(httpClient)
}
