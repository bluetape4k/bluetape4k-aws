package io.bluetape4k.aws.cloudwatch

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClientBuilder
import java.net.URI

/**
 * Builds a [CloudWatchAsyncClient].
 *
 * ```kotlin
 * val client = cloudWatchAsyncClient { region(Region.AP_NORTHEAST_2) }
 * ```
 */
inline fun cloudWatchAsyncClient(
    builder: CloudWatchAsyncClientBuilder.() -> Unit,
): CloudWatchAsyncClient =
    CloudWatchAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [CloudWatchAsyncClient] from endpoint and credentials settings.
 *
 * Nullable parameters are applied to the builder only when they are not null.
 *
 * ```kotlin
 * val client = cloudWatchAsyncClientOf(endpoint = URI("http://localhost:4566"))
 * ```
 */
inline fun cloudWatchAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: CloudWatchAsyncClientBuilder.() -> Unit = {},
): CloudWatchAsyncClient = cloudWatchAsyncClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
