package io.bluetape4k.aws.cloudwatch

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.http.SdkHttpClientProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClientBuilder
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClientBuilder
import java.net.URI

/**
 * Factory for creating [CloudWatchLogsClient] and [CloudWatchLogsAsyncClient] instances.
 */
object CloudWatchLogsClientFactory {

    /**
     * Supports synchronous [CloudWatchLogsClient] creation.
     */
    object Sync {

        /**
         * Creates a [CloudWatchLogsClient] with a DSL builder.
         *
         * ```kotlin
         * val client = CloudWatchLogsClientFactory.Sync.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: CloudWatchLogsClientBuilder.() -> Unit,
        ): CloudWatchLogsClient =
            cloudWatchLogsClient(builder)

        /**
         * Creates a [CloudWatchLogsClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = CloudWatchLogsClientFactory.Sync.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
            builder: CloudWatchLogsClientBuilder.() -> Unit = {},
        ): CloudWatchLogsClient =
            cloudWatchLogsClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }

    /**
     * Supports asynchronous [CloudWatchLogsAsyncClient] creation.
     */
    object Async {

        /**
         * Creates a [CloudWatchLogsAsyncClient] with a DSL builder.
         *
         * ```kotlin
         * val client = CloudWatchLogsClientFactory.Async.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: CloudWatchLogsAsyncClientBuilder.() -> Unit,
        ): CloudWatchLogsAsyncClient =
            cloudWatchLogsAsyncClient(builder)

        /**
         * Creates a [CloudWatchLogsAsyncClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = CloudWatchLogsClientFactory.Async.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
            builder: CloudWatchLogsAsyncClientBuilder.() -> Unit = {},
        ): CloudWatchLogsAsyncClient =
            cloudWatchLogsAsyncClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }
}
