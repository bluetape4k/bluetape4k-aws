package io.bluetape4k.aws.kotlin.secretsmanager

import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.useSafe

/**
 * AWS Kotlin SDK [SecretsManagerClient]를 생성합니다.
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
 * [SecretsManagerClient]를 생성하고 [block]을 실행한 뒤 클라이언트를 닫습니다.
 */
suspend fun <R> withSecretsManagerClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (SecretsManagerClient) -> R,
): R = secretsManagerClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
