package io.bluetape4k.aws.http

import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient

/**
 * Provider that lazily creates and exposes shared synchronous [SdkHttpClient] instances.
 *
 * ## Behavior and contract
 * - Each nested object's `httpClient` is created once on first access through `by lazy`.
 * - Created clients are registered with [ShutdownQueue] and cleaned up by the shutdown hook.
 *
 * ```kotlin
 * val client = SdkHttpClientProvider.defaultHttpClient
 * // client == SdkHttpClientProvider.Apache.httpClient
 * ```
 *
 * Reference: [AWS HTTP clients](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration.html)
 */
object SdkHttpClientProvider {

    /**
     * Provides an Apache-based synchronous HTTP client.
     *
     * ## Behavior and contract
     * - Creates the client with [ApacheHttpClient] builder defaults.
     * - Caches and reuses the instance registered with [ShutdownQueue] immediately after creation.
     *
     * ```kotlin
     * val apache = SdkHttpClientProvider.Apache.httpClient
     * // apache === SdkHttpClientProvider.Apache.httpClient
     * ```
     */
    object Apache {

        /**
         * Shared Apache-based [SdkHttpClient] instance.
         *
         * ## Behavior and contract
         * - Created once on first access and returns the same instance afterward.
         * - The created instance is registered with the shutdown queue.
         *
         * ```kotlin
         * val first = SdkHttpClientProvider.Apache.httpClient
         * val second = SdkHttpClientProvider.Apache.httpClient
         * // first === second
         * ```
         */
        val httpClient: SdkHttpClient by lazy {
            ApacheHttpClient.builder().build()
                .apply {
                    ShutdownQueue.register(this)
                }
        }
    }

    /**
     * Provides a URLConnection-based synchronous HTTP client.
     *
     * ## Behavior and contract
     * - Creates the client with the default [UrlConnectionHttpClient] builder.
     * - Reuses the instance registered with [ShutdownQueue] immediately after creation.
     *
     * ```kotlin
     * val urlConnection = SdkHttpClientProvider.UrlConnection.httpClient
     * // urlConnection === SdkHttpClientProvider.UrlConnection.httpClient
     * ```
     */
    object UrlConnection {

        /**
         * Shared URLConnection-based [SdkHttpClient] instance.
         *
         * ## Behavior and contract
         * - Created once on first access and returns the same instance afterward.
         * - The created instance is registered with the shutdown queue.
         *
         * ```kotlin
         * val first = SdkHttpClientProvider.UrlConnection.httpClient
         * val second = SdkHttpClientProvider.UrlConnection.httpClient
         * // first === second
         * ```
         */
        val httpClient: SdkHttpClient by lazy {
            UrlConnectionHttpClient.builder().build()
                .apply {
                    ShutdownQueue.register(this)
                }
        }
    }

    /**
     * Returns the Apache implementation as the default synchronous HTTP client.
     *
     * ## Behavior and contract
     * - Returns the [Apache.httpClient] reference directly.
     * - Does not create a separate new instance.
     *
     * ```kotlin
     * val defaultClient = SdkHttpClientProvider.defaultHttpClient
     * // defaultClient === SdkHttpClientProvider.Apache.httpClient
     * ```
     */
    val defaultHttpClient get() = Apache.httpClient
}
