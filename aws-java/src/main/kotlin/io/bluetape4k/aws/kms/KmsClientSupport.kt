package io.bluetape4k.aws.kms

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.KmsClientBuilder
import java.net.URI

/**
 * Creates a synchronous [KmsClient] with a DSL-style builder lambda.
 *
 * ## Behavior/Contract
 * - Applies [builder] to the builder created by [KmsClient.builder], then calls `build()`.
 * - Registers the created client instance with [ShutdownQueue].
 *
 * ```kotlin
 * val client = kmsClient {
 *     region(Region.AP_NORTHEAST_2)
 * }
 * // client.serviceName() == "kms"
 * ```
 */
inline fun kmsClient(
    builder: KmsClientBuilder.() -> Unit,
): KmsClient =
    KmsClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a synchronous [KmsClient] by specifying primary parameters directly.
 *
 * ## Behavior/Contract
 * - Applies only non-null arguments to [KmsClientBuilder].
 * - Always configures [httpClient] with `httpClient(httpClient)`.
 * - Calls [builder] last, then creates and registers the client through [kmsClient].
 *
 * Example:
 * ```kotlin
 * val client = kmsClientOf(
 *     endpointOverride = URI.create("http://localhost:4566"),
 *     region = Region.US_EAST_1,
 *     credentialsProvider = StaticCredentialsProvider.create(...)
 * )
 * ```
 */
inline fun kmsClientOf(
    endpointOverride: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: KmsClientBuilder.() -> Unit = {},
): KmsClient = kmsClient {
    endpointOverride?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
