package io.bluetape4k.aws.http

import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.http.async.SdkAsyncHttpClient

/**
 * Provider that lazily creates and exposes shared async [SdkAsyncHttpClient] instances.
 *
 * ## Behavior and contract
 * - Each nested object's `httpClient` is created once on first access through `by lazy`.
 * - Created clients are registered with [ShutdownQueue] and cleaned up during shutdown.
 *
 * ```kotlin
 * val client = SdkAsyncHttpClientProvider.defaultHttpClient
 * // client === SdkAsyncHttpClientProvider.Netty.httpClient
 * ```
 *
 * Reference: [AWS HTTP clients](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration.html)
 */
object SdkAsyncHttpClientProvider {

    /**
     * Provides a Netty NIO-based async HTTP client.
     *
     * ## Behavior and contract
     * - Creates the client with [nettyNioAsyncHttpClientOf] defaults.
     * - Reuses the instance registered with [ShutdownQueue] immediately after creation.
     *
     * ```kotlin
     * val netty = SdkAsyncHttpClientProvider.Netty.httpClient
     * // netty === SdkAsyncHttpClientProvider.Netty.httpClient
     * ```
     */
    object Netty {
        /**
         * Shared Netty-based [SdkAsyncHttpClient] instance.
         *
         * ## Behavior and contract
         * - Created once on first access and returns the same instance afterward.
         * - The created instance is registered with the shutdown queue.
         *
         * ```kotlin
         * val first = SdkAsyncHttpClientProvider.Netty.httpClient
         * val second = SdkAsyncHttpClientProvider.Netty.httpClient
         * // first === second
         * ```
         */
        @JvmStatic
        val httpClient: SdkAsyncHttpClient by lazy {
            nettyNioAsyncHttpClientOf().apply {
                ShutdownQueue.register(this)
            }
        }
    }

    /**
     * Provides an AWS CRT-based async HTTP client.
     *
     * ## Behavior and contract
     * - Creates the client with [awsCrtAsyncHttpClientOf] defaults.
     * - Reuses the instance registered with [ShutdownQueue] immediately after creation.
     *
     * ```kotlin
     * val crt = SdkAsyncHttpClientProvider.AwsCrt.httpClient
     * // crt === SdkAsyncHttpClientProvider.AwsCrt.httpClient
     * ```
     */
    object AwsCrt {
        /**
         * Shared AWS CRT-based [SdkAsyncHttpClient] instance.
         *
         * ## Behavior and contract
         * - Created once on first access and returns the same instance afterward.
         * - The created instance is registered with the shutdown queue.
         *
         * ```kotlin
         * val first = SdkAsyncHttpClientProvider.AwsCrt.httpClient
         * val second = SdkAsyncHttpClientProvider.AwsCrt.httpClient
         * // first === second
         * ```
         */
        @JvmStatic
        val httpClient: SdkAsyncHttpClient by lazy {
            awsCrtAsyncHttpClientOf().apply {
                ShutdownQueue.register(this)
            }
        }
    }

    /**
     * Returns the Netty implementation as the default async HTTP client.
     *
     * ## Behavior and contract
     * - Returns the [Netty.httpClient] reference directly.
     * - Does not create a new instance on each call.
     *
     * ```kotlin
     * val defaultClient = SdkAsyncHttpClientProvider.defaultHttpClient
     * // defaultClient === SdkAsyncHttpClientProvider.Netty.httpClient
     * ```
     */
    val defaultHttpClient: SdkAsyncHttpClient get() = Netty.httpClient
}
