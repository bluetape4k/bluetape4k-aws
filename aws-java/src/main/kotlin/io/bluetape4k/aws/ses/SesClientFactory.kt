package io.bluetape4k.aws.ses

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.http.SdkHttpClientProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesAsyncClient
import software.amazon.awssdk.services.ses.SesAsyncClientBuilder
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.SesClientBuilder
import java.net.URI

/**
 * Factory for creating [SesClient] and [SesAsyncClient] instances.
 */
object SesClientFactory {

    /**
     * Supports synchronous [SesClient] creation.
     */
    object Sync {

        /**
         * Creates a [SesClient] with a DSL builder.
         *
         * ```kotlin
         * val client = SesClientFactory.Sync.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: SesClientBuilder.() -> Unit,
        ): SesClient = sesClient(builder)

        /**
         * Creates a [SesClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = SesClientFactory.Sync.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
            builder: SesClientBuilder.() -> Unit = {},
        ): SesClient =
            create {
                endpointOverride?.let { endpointOverride(it) }
                region?.let { region(it) }
                credentialsProvider?.let { credentialsProvider(it) }
                httpClient(httpClient)

                builder()
            }
    }

    /**
     * Supports asynchronous [SesAsyncClient] creation.
     */
    object Async {

        /**
         * Creates a [SesAsyncClient] with a DSL builder.
         *
         * ```kotlin
         * val client = SesClientFactory.Async.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: SesAsyncClientBuilder.() -> Unit,
        ): SesAsyncClient = sesAsyncClient(builder)

        /**
         * Creates a [SesAsyncClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = SesClientFactory.Async.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
            builder: SesAsyncClientBuilder.() -> Unit = {},
        ): SesAsyncClient =
            create {
                endpointOverride?.let { endpointOverride(it) }
                region?.let { region(it) }
                credentialsProvider?.let { credentialsProvider(it) }
                httpClient(httpClient)

                builder()
            }
    }
}
