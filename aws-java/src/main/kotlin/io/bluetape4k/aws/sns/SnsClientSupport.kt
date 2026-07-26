package io.bluetape4k.aws.sns

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.SnsClientBuilder
import java.net.URI

/**
 * Builds a [SnsClient].
 *
 * ```kotlin
 * val result = snsClient { region(Region.AP_NORTHEAST_2) }
 * // result == SnsClient instance
 * ```
 */
inline fun snsClient(builder: SnsClientBuilder.() -> Unit): SnsClient =
    SnsClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [SnsClient] from [Region].
 *
 * [httpClient] uses the default HTTP client, and the created client is registered with [ShutdownQueue].
 *
 * ```kotlin
 * val result = snsClientOf(Region.AP_NORTHEAST_2)
 * // result == SnsClient instance
 * ```
 */
inline fun snsClientOf(
    region: Region,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: SnsClientBuilder.() -> Unit = {},
): SnsClient = snsClient {
    region(region)
    httpClient(httpClient)

    builder()
}

/**
 * Creates a [SnsClient] from endpoint and credentials settings.
 *
 * Nullable parameters are applied to the builder only when they are not null.
 *
 * ```kotlin
 * val result = snsClientOf(endpoint = URI("http://localhost:4566"))
 * // result == SnsClient instance
 * ```
 */
inline fun snsClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: SnsClientBuilder.() -> Unit = {},
): SnsClient = snsClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
