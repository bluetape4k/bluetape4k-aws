package io.bluetape4k.aws.kotlin.ssm

import aws.sdk.kotlin.services.ssm.SsmClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.useSafe

/**
 * Creates an AWS Kotlin SDK [SsmClient].
 */
inline fun ssmClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: SsmClient.Config.Builder.() -> Unit = {},
): SsmClient {
    endpointUrl?.let { it.host.toString().requireNotBlank("endpointUrl.host") }

    return SsmClient {
        endpointUrl?.let { this.endpointUrl = it }
        region?.let { this.region = it }
        credentialsProvider?.let { this.credentialsProvider = it }
        httpClient?.let { this.httpClient = it }

        builder()
    }
}

/**
 * Creates an [SsmClient], runs [block], and closes the client.
 */
suspend fun <R> withSsmClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (SsmClient) -> R,
): R = ssmClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
