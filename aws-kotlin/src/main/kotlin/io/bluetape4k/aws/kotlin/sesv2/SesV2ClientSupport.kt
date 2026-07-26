package io.bluetape4k.aws.kotlin.sesv2

import aws.sdk.kotlin.services.sesv2.SesV2Client
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * Creates an AWS Kotlin SDK [SesV2Client] instance.
 *
 * ```kotlin
 * val client = sesV2ClientOf(
 *     endpointUrl = Url.parse("http://localhost:4566"),
 *     region = "us-east-1",
 *     credentialsProvider = credentialsProvider
 * )
 * ```
 *
 * @param endpointUrl SES V2 service endpoint URL, or `null` to use the default AWS endpoint.
 * @param region AWS Region, or `null` to resolve it from the environment.
 * @param credentialsProvider AWS credentials provider, or `null` to use the default credentials chain.
 * @param httpClient optional externally managed HTTP engine. Omit it to let the SDK manage engine ownership.
 * @param builder additional configuration block for [SesV2Client.Config.Builder].
 * @return configured [SesV2Client] instance.
 */
inline fun sesV2ClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: SesV2Client.Config.Builder.() -> Unit = {},
): SesV2Client = SesV2Client {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }

    builder()
}

/**
 * Creates a [SesV2Client], runs [block], and closes the client automatically.
 *
 * When the SDK owns the internal HTTP engine, closing the client also shuts down that engine.
 *
 * ```kotlin
 * withSesV2Client(endpointUrl, region, credentialsProvider) { client ->
 *     client.sendEmail { ... }
 * }
 * ```
 *
 * @param block suspend block to run with the client. AWS SDK operations are suspend functions, so this block is suspend too.
 */
suspend fun <R> withSesV2Client(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (SesV2Client) -> R,
): R = sesV2ClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
