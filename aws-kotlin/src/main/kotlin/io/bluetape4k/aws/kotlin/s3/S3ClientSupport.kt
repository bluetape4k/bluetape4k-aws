package io.bluetape4k.aws.kotlin.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * Creates an [S3Client].
 *
 * ```kotlin
 * val s3Client = s3ClientOf(
 *    endpointUrl = Url.parse("http://localhost:4566"),
 *    region = "us-west-2",
 *    credentialsProvider = StaticCredentialsProvider { accessKeyId = "test"; secretAccessKey = "test" }
 * ) {
 *    clientName = "bluetape4k-s3-client"
 * }
 * ```
 *
 * @param endpointUrl S3 endpoint URL
 * @param region AWS Region
 * @param credentialsProvider AWS credentials provider
 * @param httpClient optional externally managed HTTP engine. Omit it to let the SDK manage engine ownership.
 * @param builder configures [S3Client.Config] through [S3Client.Config.Builder]
 * @return the [S3Client]
 */
inline fun s3ClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: S3Client.Config.Builder.() -> Unit = {},
): S3Client =
    S3Client {
        endpointUrl?.let { this.endpointUrl = it }
        region?.let { this.region = it }
        credentialsProvider?.let { this.credentialsProvider = it }
        httpClient?.let { this.httpClient = it }

        builder()
    }

/**
 * Creates an [S3Client], executes [block], and closes the client automatically.
 *
 * The SDK manages its internal HTTP engine, so closing the client also shuts down the engine.
 *
 * ```kotlin
 * withS3Client(endpointUrl, region, credentialsProvider) { client ->
 *     client.putObject { ... }
 * }
 * ```
 *
 * @param block suspending block; AWS SDK operations are suspend functions
 */
suspend fun <R> withS3Client(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (S3Client) -> R,
): R = withS3Client(
    clientFactory = { s3ClientOf(endpointUrl, region, credentialsProvider) },
    block = block,
)

/**
 * Runs a block with a client created by [clientFactory] and closes that client
 * on normal return, failure, and coroutine cancellation.
 *
 * This internal seam keeps the public helper tied to the same ownership path
 * while allowing deterministic lifecycle regression tests without network I/O.
 */
internal suspend fun <R> withS3Client(
    clientFactory: () -> S3Client,
    block: suspend (S3Client) -> R,
): R = clientFactory().useSafe { client ->
    block(client)
}
