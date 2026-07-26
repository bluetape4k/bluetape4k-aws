package io.bluetape4k.aws.sns

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.SnsAsyncClientBuilder
import java.net.URI

/**
 * Builds a [SnsAsyncClient].
 *
 * ```kotlin
 * val client = snsAsyncClient { region(Region.AP_NORTHEAST_2) }
 * // client != null
 * ```
 */
inline fun snsAsyncClient(
    builder: SnsAsyncClientBuilder.() -> Unit,
): SnsAsyncClient =
    SnsAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [SnsAsyncClient] from endpoint and credentials settings.
 *
 * ```kotlin
 * val client = snsAsyncClientOf(endpoint = URI("http://localhost:4566"), region = Region.AP_NORTHEAST_2)
 * // client != null
 * ```
 */
inline fun snsAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: SnsAsyncClientBuilder.() -> Unit = {},
): SnsAsyncClient = snsAsyncClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }

    httpClient(httpClient)

    builder()
}
