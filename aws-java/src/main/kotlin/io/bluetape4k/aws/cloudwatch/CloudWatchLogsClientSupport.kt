package io.bluetape4k.aws.cloudwatch

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClientBuilder
import java.net.URI

/**
 * Builds a [CloudWatchLogsClient].
 *
 * ```kotlin
 * val client = cloudWatchLogsClient { region(Region.AP_NORTHEAST_2) }
 * ```
 */
inline fun cloudWatchLogsClient(
    builder: CloudWatchLogsClientBuilder.() -> Unit,
): CloudWatchLogsClient =
    CloudWatchLogsClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [CloudWatchLogsClient] from [Region].
 *
 * [httpClient] uses the default HTTP client, and the created client is registered with [ShutdownQueue].
 *
 * ```kotlin
 * val client = cloudWatchLogsClientOf(Region.AP_NORTHEAST_2)
 * ```
 */
inline fun cloudWatchLogsClientOf(
    region: Region,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: CloudWatchLogsClientBuilder.() -> Unit = {},
): CloudWatchLogsClient = cloudWatchLogsClient {
    region(region)
    httpClient(httpClient)

    builder()
}

/**
 * Creates a [CloudWatchLogsClient] from endpoint and credentials settings.
 *
 * Nullable parameters are applied to the builder only when they are not null.
 *
 * ```kotlin
 * val client = cloudWatchLogsClientOf(endpoint = URI("http://localhost:4566"))
 * ```
 */
inline fun cloudWatchLogsClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: CloudWatchLogsClientBuilder.() -> Unit = {},
): CloudWatchLogsClient = cloudWatchLogsClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
