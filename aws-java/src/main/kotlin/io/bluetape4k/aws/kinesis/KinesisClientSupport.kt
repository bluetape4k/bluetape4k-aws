package io.bluetape4k.aws.kinesis

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.KinesisClientBuilder
import java.net.URI

/**
 * Builds a [KinesisClient].
 *
 * ```kotlin
 * val client = kinesisClient { region(Region.AP_NORTHEAST_2) }
 * ```
 */
inline fun kinesisClient(
    builder: KinesisClientBuilder.() -> Unit,
): KinesisClient =
    KinesisClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [KinesisClient] from [Region].
 *
 * [httpClient] uses the default HTTP client, and the created client is registered with [ShutdownQueue].
 *
 * ```kotlin
 * val client = kinesisClientOf(Region.AP_NORTHEAST_2)
 * ```
 */
inline fun kinesisClientOf(
    region: Region,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: KinesisClientBuilder.() -> Unit = {},
): KinesisClient = kinesisClient {
    region(region)
    httpClient(httpClient)

    builder()
}

/**
 * Creates a [KinesisClient] from endpoint and credentials settings.
 *
 * Nullable parameters are applied to the builder only when they are not null.
 *
 * ```kotlin
 * val client = kinesisClientOf(endpoint = URI("http://localhost:4566"))
 * ```
 */
inline fun kinesisClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: KinesisClientBuilder.() -> Unit = {},
): KinesisClient = kinesisClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
