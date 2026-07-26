package io.bluetape4k.aws.kms

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.logging.KLogging
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsAsyncClient
import software.amazon.awssdk.services.kms.KmsAsyncClientBuilder
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.KmsClientBuilder
import java.net.URI

/**
 * Factory that groups KMS synchronous and asynchronous client creation.
 *
 * ## Behavior/Contract
 * - Delegates actual client creation to [kmsClient], [kmsClientOf], [kmsAsyncClient], and [kmsAsyncClientOf].
 * - Clients returned by factory methods are registered with [io.bluetape4k.utils.ShutdownQueue] according to
 * the delegated function behavior.
 *
 * ```kotlin
 * val syncClient = KmsClientFactory.Sync.create { region(Region.AP_NORTHEAST_2) }
 * val asyncClient = KmsClientFactory.Async.create { region(Region.AP_NORTHEAST_2) }
 * // syncClient.serviceName() == "kms"
 * ```
 */
object KmsClientFactory: KLogging() {

    /**
     * Provides synchronous [KmsClient] creation entry points.
     *
     * ## Behavior/Contract
     * - Both `create` overloads delegate to the [kmsClient] function family.
     *
     * ```kotlin
     * val client = KmsClientFactory.Sync.create {
     *     region(Region.AP_NORTHEAST_2)
     * }
     * // client.serviceName() == "kms"
     * ```
     */
    object Sync {

        /**
         * Creates a synchronous [KmsClient] from a builder lambda.
         *
         * ## Behavior/Contract
         * - Passes the received [builder] directly to [kmsClient].
         *
         * ```kotlin
         * val client = KmsClientFactory.Sync.create {
         *     region(Region.AP_NORTHEAST_2)
         * }
         * // client.serviceName() == "kms"
         * ```
         */
        inline fun create(
            builder: KmsClientBuilder.() -> Unit,
        ): KmsClient = kmsClient(builder)

        /**
         * Creates a synchronous [KmsClient] from primary connection settings.
         *
         * ## Behavior/Contract
         * - Passes arguments to [kmsClientOf] in order.
         * - When [credentialsProvider] is omitted, default credential-chain resolution follows AWS SDK builder behavior.
         *
         * ```kotlin
         * val client = KmsClientFactory.Sync.create(
         *     endpointOverride = URI.create("http://localhost:4566"),
         *     region = Region.US_EAST_1
         * )
         * // client.serviceName() == "kms"
         * ```
         */
        inline fun create(
            endpointOverride: URI,
            region: Region,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
            builder: KmsClientBuilder.() -> Unit = {},
        ): KmsClient =
            kmsClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }

    /**
     * Provides asynchronous [KmsAsyncClient] creation entry points.
     *
     * ## Behavior/Contract
     * - Both `create` overloads delegate to the [kmsAsyncClient] function family.
     *
     * ```kotlin
     * val client = KmsClientFactory.Async.create {
     *     region(Region.AP_NORTHEAST_2)
     * }
     * // client.serviceName() == "kms"
     * ```
     */
    object Async {
        /**
         * Creates an asynchronous [KmsAsyncClient] from a builder lambda.
         *
         * ## Behavior/Contract
         * - Passes the received [builder] directly to [kmsAsyncClient].
         *
         * ```kotlin
         * val client = KmsClientFactory.Async.create {
         *     region(Region.AP_NORTHEAST_2)
         * }
         * // client.serviceName() == "kms"
         * ```
         */
        inline fun create(
            builder: KmsAsyncClientBuilder.() -> Unit,
        ): KmsAsyncClient = kmsAsyncClient(builder)

        /**
         * Creates an asynchronous [KmsAsyncClient] from primary connection settings.
         *
         * ## Behavior/Contract
         * - Passes arguments to [kmsAsyncClientOf] in order.
         * - When [credentialsProvider] is omitted, default credential-chain resolution follows AWS SDK builder behavior.
         *
         * ```kotlin
         * val client = KmsClientFactory.Async.create(
         *     endpointOverride = URI.create("http://localhost:4566"),
         *     region = Region.US_EAST_1
         * )
         * // client.serviceName() == "kms"
         * ```
         */
        inline fun create(
            endpointOverride: URI,
            region: Region,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
            builder: KmsAsyncClientBuilder.() -> Unit = {},
        ): KmsAsyncClient =
            kmsAsyncClientOf(endpointOverride, region, credentialsProvider, httpClient, builder)
    }
}
