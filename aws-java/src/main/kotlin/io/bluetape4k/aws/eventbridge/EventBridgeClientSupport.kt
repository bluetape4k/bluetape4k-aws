package io.bluetape4k.aws.eventbridge

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.EventBridgeClientBuilder
import java.net.URI

/**
 * AWS SDK v2 [EventBridgeClient]를 생성합니다.
 *
 * 생성한 클라이언트는 다른 bluetape4k AWS 서비스의 Java SDK 래퍼 수명 주기 계약과 마찬가지로
 * [ShutdownQueue]에 등록합니다.
 *
 * ```kotlin
 * val client = eventBridgeClient { region(Region.AP_NORTHEAST_2) }
 * ```
 */
inline fun eventBridgeClient(
    builder: EventBridgeClientBuilder.() -> Unit,
): EventBridgeClient =
    EventBridgeClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * 리전용 [EventBridgeClient]를 생성합니다.
 */
inline fun eventBridgeClientOf(
    region: Region,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: EventBridgeClientBuilder.() -> Unit = {},
): EventBridgeClient = eventBridgeClient {
    region(region)
    httpClient(httpClient)
    builder()
}

/**
 * 선택적인 엔드포인트, 리전, 자격 증명으로 [EventBridgeClient]를 생성합니다.
 */
inline fun eventBridgeClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: EventBridgeClientBuilder.() -> Unit = {},
): EventBridgeClient = eventBridgeClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)
    builder()
}
