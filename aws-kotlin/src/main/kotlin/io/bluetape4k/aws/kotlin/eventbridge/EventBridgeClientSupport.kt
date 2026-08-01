package io.bluetape4k.aws.kotlin.eventbridge

import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * AWS Kotlin SDK [EventBridgeClient]를 생성합니다.
 *
 * 반환된 클라이언트는 호출자가 소유합니다. 블록 실행 후 닫아야 하는 단기 클라이언트에는
 * [withEventBridgeClient]를 사용하세요.
 */
inline fun eventBridgeClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: EventBridgeClient.Config.Builder.() -> Unit = {},
): EventBridgeClient = EventBridgeClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }
    builder()
}

/**
 * [EventBridgeClient]를 생성하고 [block]을 실행한 뒤 클라이언트를 닫습니다.
 */
suspend fun <R> withEventBridgeClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (EventBridgeClient) -> R,
): R = eventBridgeClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
