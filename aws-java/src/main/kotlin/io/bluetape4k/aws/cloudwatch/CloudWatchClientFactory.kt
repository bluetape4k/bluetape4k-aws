package io.bluetape4k.aws.cloudwatch

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.http.SdkHttpClientProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClientBuilder
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchClientBuilder
import java.net.URI

/**
 * Factory for creating [CloudWatchClient] and [CloudWatchAsyncClient] instances.
 */
object CloudWatchClientFactory {

    /**
     * Supports synchronous [CloudWatchClient] creation.
     */
    object Sync {

        /**
         * Creates a [CloudWatchClient] with a DSL builder.
         *
         * ```kotlin
         * val client = CloudWatchClientFactory.Sync.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: CloudWatchClientBuilder.() -> Unit,
        ): CloudWatchClient =
            cloudWatchClient(builder)

        /**
         * Creates a [CloudWatchClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = CloudWatchClientFactory.Sync.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
            builder: CloudWatchClientBuilder.() -> Unit = {},
        ): CloudWatchClient =
            cloudWatchClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }

    /**
     * Supports asynchronous [CloudWatchAsyncClient] creation.
     */
    object Async {

        /**
         * Creates a [CloudWatchAsyncClient] with a DSL builder.
         *
         * ```kotlin
         * val client = CloudWatchClientFactory.Async.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: CloudWatchAsyncClientBuilder.() -> Unit,
        ): CloudWatchAsyncClient =
            cloudWatchAsyncClient(builder)

        /**
         * Creates a [CloudWatchAsyncClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = CloudWatchClientFactory.Async.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
            builder: CloudWatchAsyncClientBuilder.() -> Unit = {},
        ): CloudWatchAsyncClient =
            cloudWatchAsyncClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }
}
