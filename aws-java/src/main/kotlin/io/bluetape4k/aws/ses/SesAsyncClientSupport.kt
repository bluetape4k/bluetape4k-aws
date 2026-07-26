package io.bluetape4k.aws.ses

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesAsyncClient
import software.amazon.awssdk.services.ses.SesAsyncClientBuilder
import software.amazon.awssdk.services.ses.endpoints.SesEndpointProvider

/**
 * Builds a [sesAsyncClient].
 *
 * ```kotlin
 * val client = SesAsyncClient {
 *     credentialsProvider(credentialsProvider)
 *     endpointOverride(endpoint)
 *     region(region)
 * }
 * val response = client.send(request).await()
 * ```
 *
 * @param builder initialization lambda using [SesAsyncClientBuilder].
 * @return [sesAsyncClient] instance.
 */
inline fun sesAsyncClient(
    builder: SesAsyncClientBuilder.() -> Unit,
): SesAsyncClient {
    return SesAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }
}

/**
 * Creates a [SesAsyncClient].
 *
 * ```kotlin
 * val client = sesAsyncClientOf(region) {
 *     credentialsProvider(credentialsProvider)
 *     endpointOverride(endpoint)
 * }
 * val response = client.send(request).await()
 * ```
 *
 * @param region [Region] value.
 * @param builder initialization lambda using [SesAsyncClientBuilder].
 * @return [SesAsyncClient] instance.
 */
inline fun sesAsyncClientOf(
    region: Region,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: SesAsyncClientBuilder.() -> Unit = {},
): SesAsyncClient = sesAsyncClient {
    region(region)
    httpClient(httpClient)

    builder()
}

/**
 * Creates a [SesAsyncClient].
 *
 * ```kotlin
 * val client = sesAsyncClientOf(endpointProvider) {
 *     credentialsProvider(credentialsProvider)
 *     endpointOverride(endpoint)
 * }
 * val response = client.send(request).await()
 * ```
 *
 * @param endpointProvider [SesEndpointProvider] endpoint provider.
 * @param builder initialization lambda using [SesAsyncClientBuilder].
 * @return [SesAsyncClient] instance.
 */
inline fun sesAsyncClientOf(
    endpointProvider: SesEndpointProvider? = null,
    region: Region? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: SesAsyncClientBuilder.() -> Unit = {},
): SesAsyncClient = sesAsyncClient {

    endpointProvider?.let { endpointProvider(it) }
    region?.let { region(it) }
    httpClient(httpClient)

    builder()
}
