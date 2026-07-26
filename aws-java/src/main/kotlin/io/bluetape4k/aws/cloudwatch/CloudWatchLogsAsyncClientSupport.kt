package io.bluetape4k.aws.cloudwatch

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClientBuilder
import java.net.URI

/**
 * Builds a [CloudWatchLogsAsyncClient].
 *
 * ```kotlin
 * val client = cloudWatchLogsAsyncClient { region(Region.AP_NORTHEAST_2) }
 * ```
 */
inline fun cloudWatchLogsAsyncClient(
    builder: CloudWatchLogsAsyncClientBuilder.() -> Unit,
): CloudWatchLogsAsyncClient =
    CloudWatchLogsAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [CloudWatchLogsAsyncClient] from endpoint and credentials settings.
 *
 * Nullable parameters are applied to the builder only when they are not null.
 *
 * ```kotlin
 * val client = cloudWatchLogsAsyncClientOf(endpoint = URI("http://localhost:4566"))
 * ```
 */
inline fun cloudWatchLogsAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: CloudWatchLogsAsyncClientBuilder.() -> Unit = {},
): CloudWatchLogsAsyncClient = cloudWatchLogsAsyncClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
