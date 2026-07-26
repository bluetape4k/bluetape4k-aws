package io.bluetape4k.aws.kinesis

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder
import java.net.URI

/**
 * Builds a [KinesisAsyncClient].
 *
 * ```kotlin
 * val client = kinesisAsyncClient { region(Region.AP_NORTHEAST_2) }
 * ```
 */
inline fun kinesisAsyncClient(
    builder: KinesisAsyncClientBuilder.() -> Unit,
): KinesisAsyncClient =
    KinesisAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [KinesisAsyncClient] from endpoint and credentials settings.
 *
 * Nullable parameters are applied to the builder only when they are not null.
 *
 * ```kotlin
 * val client = kinesisAsyncClientOf(endpoint = URI("http://localhost:4566"))
 * ```
 */
inline fun kinesisAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: KinesisAsyncClientBuilder.() -> Unit = {},
): KinesisAsyncClient = kinesisAsyncClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }

    httpClient(httpClient)

    builder()
}
