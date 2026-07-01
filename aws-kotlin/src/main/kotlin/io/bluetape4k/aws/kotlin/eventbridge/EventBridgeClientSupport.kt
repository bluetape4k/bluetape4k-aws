package io.bluetape4k.aws.kotlin.eventbridge

import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.aws.kotlin.http.HttpClientEngineProvider
import io.bluetape4k.support.useSafe

/**
 * Builds an AWS Kotlin SDK [EventBridgeClient].
 *
 * The returned client is caller-owned. Use [withEventBridgeClient] for
 * short-lived clients that should be closed after the block.
 */
inline fun eventBridgeClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = HttpClientEngineProvider.defaultHttpEngine,
    crossinline builder: EventBridgeClient.Config.Builder.() -> Unit = {},
): EventBridgeClient = EventBridgeClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }
    builder()
}

/**
 * Builds an [EventBridgeClient], runs [block], and closes the client.
 */
suspend fun <R> withEventBridgeClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (EventBridgeClient) -> R,
): R = eventBridgeClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
