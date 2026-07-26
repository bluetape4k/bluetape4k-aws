package io.bluetape4k.aws.sts

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.http.SdkHttpClientProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.StsAsyncClient
import software.amazon.awssdk.services.sts.StsAsyncClientBuilder
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.StsClientBuilder
import java.net.URI

/**
 * Factory for creating [StsClient] and [StsAsyncClient] instances.
 */
object StsClientFactory {

    /**
     * Supports synchronous [StsClient] creation.
     */
    object Sync {

        /**
         * Creates a [StsClient] with a DSL block.
         *
         * ```kotlin
         * val client = StsClientFactory.Sync.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: StsClientBuilder.() -> Unit,
        ): StsClient =
            stsClient(builder)

        /**
         * Creates a [StsClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = StsClientFactory.Sync.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
            builder: StsClientBuilder.() -> Unit = {},
        ): StsClient =
            stsClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }

    /**
     * Supports asynchronous [StsAsyncClient] creation.
     */
    object Async {

        /**
         * Creates a [StsAsyncClient] with a DSL block.
         *
         * ```kotlin
         * val client = StsClientFactory.Async.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: StsAsyncClientBuilder.() -> Unit,
        ): StsAsyncClient =
            stsAsyncClient(builder)

        /**
         * Creates a [StsAsyncClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = StsClientFactory.Async.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
            builder: StsAsyncClientBuilder.() -> Unit = {},
        ): StsAsyncClient =
            stsAsyncClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }
}
