package io.bluetape4k.aws.kotlin.sts

import aws.sdk.kotlin.services.sts.StsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * Creates an AWS Kotlin SDK [StsClient].
 *
 * AWS Security Token Service (STS) issues temporary, limited-privilege credentials
 * that applications can use to access AWS resources.
 *
 * Example:
 * ```kotlin
 * val client = stsClientOf(
 *     endpointUrl = Url.parse("http://localhost:4566"),
 *     region = "us-east-1",
 *     credentialsProvider = myCredentialsProvider
 * )
 * ```
 *
 * @param endpointUrl STS service endpoint URL. When null, the SDK uses the default AWS endpoint.
 * @param region AWS region. When null, the SDK resolves the region from its environment chain.
 * @param credentialsProvider AWS credentials provider. When null, the SDK uses the default credentials chain.
 * @param httpClient optional externally managed HTTP engine. Omit it to let the SDK manage engine ownership.
 * @param builder additional configuration for [StsClient.Config.Builder].
 * @return configured [StsClient] instance.
 */
inline fun stsClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: StsClient.Config.Builder.() -> Unit = {},
): StsClient = StsClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }

    builder()
}

/**
 * Creates an [StsClient], runs [block], and closes the client automatically.
 *
 * When the SDK owns the HTTP engine, closing the client closes the engine as well.
 *
 * ```kotlin
 * withStsClient(endpointUrl, region, credentialsProvider) { client ->
 *     client.getCallerIdentity()
 * }
 * ```
 *
 * @param block suspend block that receives the configured [StsClient].
 */
suspend fun <R> withStsClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (StsClient) -> R,
): R = stsClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
