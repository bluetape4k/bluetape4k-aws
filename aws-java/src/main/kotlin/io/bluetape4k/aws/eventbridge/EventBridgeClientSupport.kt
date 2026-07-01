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
 * Builds an AWS SDK v2 [EventBridgeClient].
 *
 * The created client is registered with [ShutdownQueue], matching the Java SDK
 * wrapper lifecycle contract used by sibling bluetape4k AWS services.
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
 * Builds an [EventBridgeClient] for a region.
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
 * Builds an [EventBridgeClient] with optional endpoint, region, and credentials.
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
