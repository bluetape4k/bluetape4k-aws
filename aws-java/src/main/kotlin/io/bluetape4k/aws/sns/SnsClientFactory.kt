package io.bluetape4k.aws.sns

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.http.SdkHttpClientProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.SnsAsyncClientBuilder
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.SnsClientBuilder
import java.net.URI

/**
 * Factory for creating [SnsClient] and [SnsAsyncClient] instances.
 */
object SnsClientFactory {

    /**
     * Supports synchronous [SnsClient] creation.
     */
    object Sync {

        /**
         * Creates a [SnsClient] with a DSL builder.
         *
         * ```kotlin
         * val client = SnsClientFactory.Sync.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: SnsClientBuilder.() -> Unit,
        ): SnsClient =
            snsClient(builder)

        /**
         * Creates a [SnsClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = SnsClientFactory.Sync.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
            builder: SnsClientBuilder.() -> Unit = {},
        ): SnsClient =
            snsClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }

    /**
     * Supports asynchronous [SnsAsyncClient] creation.
     */
    object Async {

        /**
         * Creates a [SnsAsyncClient] with a DSL builder.
         *
         * ```kotlin
         * val client = SnsClientFactory.Async.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: SnsAsyncClientBuilder.() -> Unit,
        ): SnsAsyncClient =
            snsAsyncClient(builder)

        /**
         * Creates a [SnsAsyncClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = SnsClientFactory.Async.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
            builder: SnsAsyncClientBuilder.() -> Unit = {},
        ): SnsAsyncClient =
            snsAsyncClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }
}
