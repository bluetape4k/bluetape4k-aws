package io.bluetape4k.aws.cloudwatch

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchClientBuilder
import java.net.URI

/**
 * Builds a [CloudWatchClient].
 *
 * ```kotlin
 * val client = cloudWatchClient { region(Region.AP_NORTHEAST_2) }
 * ```
 */
inline fun cloudWatchClient(
    builder: CloudWatchClientBuilder.() -> Unit,
): CloudWatchClient =
    CloudWatchClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [CloudWatchClient] from [Region].
 *
 * [httpClient] uses the default HTTP client, and the created client is registered with [ShutdownQueue].
 *
 * ```kotlin
 * val client = cloudWatchClientOf(Region.AP_NORTHEAST_2)
 * ```
 */
inline fun cloudWatchClientOf(
    region: Region,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: CloudWatchClientBuilder.() -> Unit = {},
): CloudWatchClient = cloudWatchClient {
    region(region)
    httpClient(httpClient)

    builder()
}

/**
 * Creates a [CloudWatchClient] from endpoint and credentials settings.
 *
 * Nullable parameters are applied to the builder only when they are not null.
 *
 * ```kotlin
 * val client = cloudWatchClientOf(endpoint = URI("http://localhost:4566"))
 * ```
 */
inline fun cloudWatchClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: CloudWatchClientBuilder.() -> Unit = {},
): CloudWatchClient = cloudWatchClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
