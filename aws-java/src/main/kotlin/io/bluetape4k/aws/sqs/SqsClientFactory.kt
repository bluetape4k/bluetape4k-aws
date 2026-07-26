package io.bluetape4k.aws.sqs

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.http.SdkHttpClientProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.SqsClientBuilder
import java.net.URI

/**
 * Factory for creating [SqsClient] and [SqsAsyncClient] instances.
 */
object SqsClientFactory {

    /**
     * Supports synchronous [SqsClient] creation.
     */
    object Sync {

        /**
         * Creates a [SqsClient] with a DSL builder.
         *
         * ```kotlin
         * val client = SqsClientFactory.Sync.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: SqsClientBuilder.() -> Unit,
        ): SqsClient = sqsClient(builder)

        /**
         * Creates a [SqsClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = SqsClientFactory.Sync.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
            builder: SqsClientBuilder.() -> Unit = {},
        ): SqsClient = sqsClientOf(
            endpointOverride,
            region,
            credentialsProvider,
            httpClient,
            builder
        )
    }

    /**
     * Supports asynchronous [SqsAsyncClient] creation.
     */
    object Async {

        /**
         * Creates a [SqsAsyncClient] with a DSL builder.
         *
         * ```kotlin
         * val client = SqsClientFactory.Async.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         */
        inline fun create(
            builder: SqsAsyncClientBuilder.() -> Unit,
        ): SqsAsyncClient = sqsAsyncClient(builder)

        /**
         * Creates a [SqsAsyncClient] from endpoint, region, and credentials settings.
         *
         * ```kotlin
         * val client = SqsClientFactory.Async.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
            builder: SqsAsyncClientBuilder.() -> Unit = {},
        ): SqsAsyncClient = sqsAsyncClientOf(
            endpointOverride,
            region,
            credentialsProvider,
            httpClient,
            builder,
        )
    }
}
