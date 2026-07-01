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
 * Builds an AWS SDK v2 [EventBridgeAsyncClient].
 *
 * The created client is registered with [ShutdownQueue]. Coroutine helpers await
 * the returned futures and preserve cancellation/SDK exceptions.
 */
inline fun eventBridgeAsyncClient(
    builder: EventBridgeAsyncClientBuilder.() -> Unit,
): EventBridgeAsyncClient =
    EventBridgeAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Builds an [EventBridgeAsyncClient] with optional endpoint, region, and credentials.
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
