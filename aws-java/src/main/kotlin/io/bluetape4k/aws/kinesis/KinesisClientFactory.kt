package io.bluetape4k.aws.kinesis

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.http.SdkHttpClientProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.KinesisClientBuilder
import java.net.URI

/**
 * Factory for creating [KinesisClient] and [KinesisAsyncClient] instances.
 */
object KinesisClientFactory {

    /**
     * Supports synchronous [KinesisClient] creation.
     */
    object Sync {

        /**
         * Creates a [KinesisClient] with a DSL builder block.
         *
         * ```kotlin
         * val client = KinesisClientFactory.Sync.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: KinesisClientBuilder.() -> Unit,
        ): KinesisClient =
            kinesisClient(builder)

        /**
         * Creates a [KinesisClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = KinesisClientFactory.Sync.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
            builder: KinesisClientBuilder.() -> Unit = {},
        ): KinesisClient =
            kinesisClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }

    /**
     * Supports asynchronous [KinesisAsyncClient] creation.
     */
    object Async {

        /**
         * Creates a [KinesisAsyncClient] with a DSL builder block.
         *
         * ```kotlin
         * val client = KinesisClientFactory.Async.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: KinesisAsyncClientBuilder.() -> Unit,
        ): KinesisAsyncClient =
            kinesisAsyncClient(builder)

        /**
         * Creates a [KinesisAsyncClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = KinesisClientFactory.Async.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
            builder: KinesisAsyncClientBuilder.() -> Unit = {},
        ): KinesisAsyncClient =
            kinesisAsyncClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }
}
