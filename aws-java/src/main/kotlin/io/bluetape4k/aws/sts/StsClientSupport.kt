package io.bluetape4k.aws.sts

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.StsClientBuilder
import java.net.URI

/**
 * Builds a [StsClient].
 *
 * ```kotlin
 * val client = stsClient { region(Region.AP_NORTHEAST_2) }
 * // client == StsClient instance
 * ```
 */
inline fun stsClient(
    builder: StsClientBuilder.() -> Unit,
): StsClient =
    StsClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [StsClient] from [Region].
 *
 * [httpClient] uses the default HTTP client, and the created client is registered with [ShutdownQueue].
 *
 * ```kotlin
 * val client = stsClientOf(Region.AP_NORTHEAST_2)
 * // client == StsClient instance
 * ```
 */
inline fun stsClientOf(
    region: Region,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: StsClientBuilder.() -> Unit = {},
): StsClient = stsClient {
    region(region)
    httpClient(httpClient)

    builder()
}

/**
 * Creates a [StsClient] from endpoint and credentials settings.
 *
 * Nullable parameters are reflected in the builder only when they are not null.
 *
 * ```kotlin
 * val client = stsClientOf(endpoint = URI("http://localhost:4566"))
 * // client == StsClient instance
 * ```
 */
inline fun stsClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: StsClientBuilder.() -> Unit = {},
): StsClient = stsClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
