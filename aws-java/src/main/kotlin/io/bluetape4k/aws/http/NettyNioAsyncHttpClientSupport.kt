package io.bluetape4k.aws.http

import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Creates [SdkAsyncHttpClient] with the Netty NIO builder DSL.
 *
 * ## Behavior and contract
 * - Applies [builder] to [NettyNioAsyncHttpClient.builder], then returns `build()`.
 * - Settings supplied by [builder] are reflected in the final client.
 *
 * ```kotlin
 * val client = nettyNioAsyncHttpClient {
 *     maxConcurrency(32)
 * }
 * // client != null
 * ```
 */
inline fun nettyNioAsyncHttpClient(
    builder: NettyNioAsyncHttpClient.Builder.() -> Unit,
): SdkAsyncHttpClient {
    return NettyNioAsyncHttpClient.builder().apply(builder).build()
}

/**
 * Creates a Netty NIO [SdkAsyncHttpClient] with default timeout and concurrency values.
 *
 * ## Behavior and contract
 * - Defaults are `maxConcurrency=100` and `30.seconds` for each timeout.
 * - Converts values to Java Duration internally, then sets `maxConcurrency`, `connectionMaxIdleTime`, `connectionTimeout`, `readTimeout`, and `writeTimeout` in order.
 * - Applies [builder] last so callers can override defaults.
 *
 * ```kotlin
 * val client = nettyNioAsyncHttpClientOf(maxConcurrency = 64) {
 *     writeTimeout(java.time.Duration.ofSeconds(10))
 * }
 * // client != null
 * ```
 */
inline fun nettyNioAsyncHttpClientOf(
    maxConcurrency: Int = 100,
    connectionMaxIdleTime: Duration = 30.seconds,
    connectionTimeout: Duration = 30.seconds,
    readTimeout: Duration = 30.seconds,
    writeTimeout: Duration = 30.seconds,
    builder: NettyNioAsyncHttpClient.Builder.() -> Unit = {},
): SdkAsyncHttpClient = nettyNioAsyncHttpClient {
    this.maxConcurrency(maxConcurrency)
    this.connectionMaxIdleTime(connectionMaxIdleTime.toJavaDuration())
    this.connectionTimeout(connectionTimeout.toJavaDuration())
    this.readTimeout(readTimeout.toJavaDuration())
    this.writeTimeout(writeTimeout.toJavaDuration())

    builder()
}
