package io.bluetape4k.aws.eventbridge

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClientBuilder
import java.net.URI

/**
 * AWS SDK v2 [EventBridgeAsyncClient]를 생성합니다.
 *
 * 생성한 클라이언트는 [ShutdownQueue]에 등록합니다. 코루틴 도우미는 반환된 Future를 기다리며
 * 취소와 SDK 예외를 보존합니다.
 */
inline fun eventBridgeAsyncClient(
    builder: EventBridgeAsyncClientBuilder.() -> Unit,
): EventBridgeAsyncClient =
    EventBridgeAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * 선택적인 엔드포인트, 리전, 자격 증명으로 [EventBridgeAsyncClient]를 생성합니다.
 */
inline fun eventBridgeAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: EventBridgeAsyncClientBuilder.() -> Unit = {},
): EventBridgeAsyncClient = eventBridgeAsyncClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)
    builder()
}
