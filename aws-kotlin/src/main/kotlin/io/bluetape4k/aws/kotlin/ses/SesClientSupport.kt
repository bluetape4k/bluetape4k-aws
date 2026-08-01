package io.bluetape4k.aws.kotlin.ses

import aws.sdk.kotlin.services.ses.SesClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * Creates an AWS Kotlin SDK [SesClient] instance.
 *
 * ```kotlin
 * val client = sesClientOf(
 *     endpointUrl = Url.parse("http://localhost:4566"),
 *     region = "us-east-1",
 *     credentialsProvider = credentialsProvider
 * )
 * ```
 *
 * @param endpointUrl SES service endpoint URL, or `null` to use the default AWS endpoint.
 * @param region AWS Region, or `null` to resolve it from the environment.
 * @param credentialsProvider AWS credentials provider, or `null` to use the default credentials chain.
 * @param httpClient optional externally managed HTTP engine. Omit it to let the SDK manage engine ownership.
 * @param builder additional configuration block for [SesClient.Config.Builder].
 * @return configured [SesClient] instance.
 */
inline fun sesClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: SesClient.Config.Builder.() -> Unit = {},
): SesClient = SesClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }

    builder()
}

/**
 * Creates a [SesClient], runs [block], and closes the client automatically.
 *
 * When the SDK owns the internal HTTP engine, closing the client also shuts down that engine.
 *
 * ```kotlin
 * withSesClient(endpointUrl, region, credentialsProvider) { client ->
 *     client.sendEmail { ... }
 * }
 * ```
 *
 * @param block suspend block to run with the client. AWS SDK operations are suspend functions, so this block is suspend too.
 */
suspend fun <R> withSesClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (SesClient) -> R,
): R = withSesClient(
    clientFactory = { sesClientOf(endpointUrl, region, credentialsProvider) },
    block = block,
)

/**
 * Runs a block with a client created by [clientFactory] and closes that client
 * on normal return, failure, and coroutine cancellation.
 *
 * This internal seam keeps the public helper tied to the same ownership path
 * while allowing deterministic lifecycle regression tests without network I/O.
 */
internal suspend fun <R> withSesClient(
    clientFactory: () -> SesClient,
    block: suspend (SesClient) -> R,
): R = clientFactory().useSafe { client ->
    block(client)
}
