package io.bluetape4k.aws.http

import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Creates [SdkAsyncHttpClient] with the CRT builder DSL.
 *
 * ## Behavior and contract
 * - Applies [builder] to [AwsCrtAsyncHttpClient.builder], then returns `build()`.
 * - Settings supplied by [builder] are reflected in the final client.
 *
 * Reference: [AWS CRT-based HTTP client configuration](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-crt.html)
 *
 * NOTE: Remove `netty-nio-client` from dependencies before using [AwsCrtAsyncHttpClient]. They cannot be used together.
 *
 * ```kotlin
 * val client = awsCrtAsyncHttpClient {
 *     maxConcurrency(64)
 * }
 * // client != null
 * ```
 */
inline fun awsCrtAsyncHttpClient(
    builder: AwsCrtAsyncHttpClient.Builder.() -> Unit,
): SdkAsyncHttpClient {
    return AwsCrtAsyncHttpClient.builder().apply(builder).build()
}

/**
 * Creates a CRT [SdkAsyncHttpClient] with default concurrency, buffer, and timeout settings.
 *
 * ## Behavior and contract
 * - Defaults are `maxConcurrency=100`, `readBufferSize=2*1024*1024`, `connectionMaxIdleTime=30.seconds`, `connectionTimeout=5.seconds`, and `postQuantumTlsEnabled=false`.
 * - Converts Kotlin [Duration] values to Java Duration values before applying them to the CRT builder.
 * - Applies [builder] last, so callers can selectively override defaults.
 *
 * ```kotlin
 * val client = awsCrtAsyncHttpClientOf(
 *     maxConcurrency = 128,
 *     postQuantumTlsEnabled = true,
 * )
 * // client != null
 * ```
 */
inline fun awsCrtAsyncHttpClientOf(
    maxConcurrency: Int = 100,
    readBufferSize: Long = 2 * 1024 * 1024,
    connectionMaxIdleTime: Duration = 30.seconds,
    connectionTimeout: Duration = 5.seconds,
    postQuantumTlsEnabled: Boolean = false,
    builder: AwsCrtAsyncHttpClient.Builder.() -> Unit = {},
): SdkAsyncHttpClient = awsCrtAsyncHttpClient {
    this.maxConcurrency(maxConcurrency)
    this.readBufferSizeInBytes(readBufferSize)
    this.connectionMaxIdleTime(connectionMaxIdleTime.toJavaDuration())
    this.connectionTimeout(connectionTimeout.toJavaDuration())
    this.postQuantumTlsEnabled(postQuantumTlsEnabled)

    builder()
}
