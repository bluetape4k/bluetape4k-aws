package io.bluetape4k.aws.kotlin.ssm

import aws.sdk.kotlin.services.ssm.SsmClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.useSafe

/**
 * AWS Kotlin SDK [SsmClient]를 생성합니다.
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
 * [SsmClient]를 생성하고 [block]을 실행한 뒤 클라이언트를 닫습니다.
 */
suspend fun <R> withSsmClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (SsmClient) -> R,
): R = ssmClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
