package io.bluetape4k.aws.kotlin.secretsmanager

import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.useSafe

/**
 * Creates an AWS Kotlin SDK [SecretsManagerClient].
 */
inline fun secretsManagerClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: SecretsManagerClient.Config.Builder.() -> Unit = {},
): SecretsManagerClient {
    endpointUrl?.let { it.host.toString().requireNotBlank("endpointUrl.host") }

    return SecretsManagerClient {
        endpointUrl?.let { this.endpointUrl = it }
        region?.let { this.region = it }
        credentialsProvider?.let { this.credentialsProvider = it }
        httpClient?.let { this.httpClient = it }

        builder()
    }
}

/**
 * Creates a [SecretsManagerClient], runs [block], and closes the client.
 */
suspend fun <R> withSecretsManagerClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (SecretsManagerClient) -> R,
): R = secretsManagerClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
