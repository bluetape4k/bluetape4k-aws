package io.bluetape4k.aws.kms

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsAsyncClient
import software.amazon.awssdk.services.kms.KmsAsyncClientBuilder
import java.net.URI

/**
 * Creates an asynchronous [KmsAsyncClient] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to [KmsAsyncClient.builder], then calls `build()`.
 * - Registers the created client instance with [ShutdownQueue].
 *
 * ```kotlin
 * val client = kmsAsyncClient {
 *     region(Region.AP_NORTHEAST_2)
 * }
 * // client.serviceName() == "kms"
 * ```
 */
inline fun kmsAsyncClient(
    builder: KmsAsyncClientBuilder.() -> Unit,
): KmsAsyncClient =
    KmsAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates an asynchronous [KmsAsyncClient] by specifying primary parameters directly.
 *
 * ## Behavior/Contract
 * - Applies only non-null arguments to [KmsAsyncClientBuilder].
 * - Always configures [httpClient] with `httpClient(httpClient)`.
 * - Calls [builder] last, then creates and registers the client through [kmsAsyncClient].
 *
 * Example:
 * ```kotlin
 * val client = kmsAsyncClientOf(
 *     endpointOverride = URI.create("http://localhost:4566"),
 *     region = Region.US_EAST_1,
 *     credentialsProvider = StaticCredentialsProvider.create(...)
 * )
 * val response = client.createKey { ... }.await()
 * ```
 */
inline fun kmsAsyncClientOf(
    endpointOverride: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: KmsAsyncClientBuilder.() -> Unit,
): KmsAsyncClient = kmsAsyncClient {
    endpointOverride?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
