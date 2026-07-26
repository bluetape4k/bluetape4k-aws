package io.bluetape4k.aws.kotlin.sns

import aws.sdk.kotlin.services.sns.SnsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * Creates an AWS Kotlin SDK [SnsClient] instance.
 *
 * ```kotlin
 * val client = snsClientOf(
 *     endpointUrl = Url.parse("http://localhost:4566"),
 *     region = "us-east-1",
 *     credentialsProvider = credentialsProvider
 * )
 * ```
 *
 * @param endpointUrl SNS service endpoint URL. Uses the default AWS endpoint when null.
 * @param region AWS Region. Automatically detected from the environment when null.
 * @param credentialsProvider AWS credentials provider. Uses the default credentials chain when null.
 * @param httpClient optional externally managed HTTP engine. Omit it to let the SDK manage engine ownership.
 * @param builder Lambda for applying additional settings to [SnsClient.Config.Builder].
 * @return The configured [SnsClient] instance.
 */
inline fun snsClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: SnsClient.Config.Builder.() -> Unit = {},
): SnsClient = SnsClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }

    builder()
}

/**
 * Creates an [SnsClient], executes [block], and closes the client automatically.
 *
 * The SDK owns its internal HTTP engine, so closing the client also shuts down the engine.
 *
 * ```kotlin
 * withSnsClient(endpointUrl, region, credentialsProvider) { client ->
 *     client.publish { ... }
 * }
 * ```
 *
 * @param block Suspending block because all AWS SDK operations are suspend functions.
 */
suspend fun <R> withSnsClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (SnsClient) -> R,
): R = snsClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
