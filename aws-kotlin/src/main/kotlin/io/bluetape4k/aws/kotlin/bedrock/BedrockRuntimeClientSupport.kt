package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * Verifies that a Bedrock endpoint uses HTTPS or literal loopback HTTP.
 *
 * Host names are not resolved. Plain HTTP is limited to local emulator tests.
 */
@PublishedApi
internal fun Url.requireTrustedBedrockEndpoint(): Url = apply {
    val protocol = scheme.protocolName.lowercase()
    val normalizedHost = host.toString()
        .lowercase()
        .removePrefix("[")
        .removeSuffix("]")
    require(normalizedHost.isNotBlank()) {
        "Bedrock endpoint must include a host."
    }
    val isLoopback = normalizedHost == "localhost" ||
        normalizedHost == "127.0.0.1" ||
        normalizedHost == "::1"
    require(protocol == "https" || (protocol == "http" && isLoopback)) {
        "Bedrock endpoint must use HTTPS; plain HTTP is allowed only for literal loopback tests."
    }
}

/**
 * Validates a built client and closes it exactly once when validation fails.
 */
@PublishedApi
internal fun BedrockRuntimeClient.requireTrustedBedrockConfiguration(): BedrockRuntimeClient {
    try {
        config.endpointUrl?.requireTrustedBedrockEndpoint()
    } catch (cause: Throwable) {
        try {
            close()
        } finally {
            throw cause
        }
    }
    return this
}

/**
 * Builds a caller-owned AWS Kotlin SDK [BedrockRuntimeClient].
 *
 * Explicit parameters are helper-owned and take precedence over [builder].
 * The final endpoint must use HTTPS except for literal loopback HTTP. The
 * application must add `aws.sdk.kotlin:bedrockruntime` at runtime and close
 * the returned client.
 */
inline fun bedrockRuntimeClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: BedrockRuntimeClient.Config.Builder.() -> Unit = {},
): BedrockRuntimeClient =
    BedrockRuntimeClient {
        builder()
        endpointUrl?.requireTrustedBedrockEndpoint()?.let { this.endpointUrl = it }
        region?.let { this.region = it }
        credentialsProvider?.let { this.credentialsProvider = it }
        httpClient?.let { this.httpClient = it }
    }.requireTrustedBedrockConfiguration()

/**
 * Builds a block-owned [BedrockRuntimeClient], runs [block], and closes it.
 *
 * Complete collection of any cold Flow inside [block]; an escaped Flow cannot
 * use the client after this scope closes. The application must add
 * `aws.sdk.kotlin:bedrockruntime` at runtime.
 */
suspend fun <R> withBedrockRuntimeClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    builder: BedrockRuntimeClient.Config.Builder.() -> Unit = {},
    block: suspend (BedrockRuntimeClient) -> R,
): R = withBedrockRuntimeClient(
    clientFactory = {
        bedrockRuntimeClientOf(
            endpointUrl = endpointUrl,
            region = region,
            credentialsProvider = credentialsProvider,
            httpClient = httpClient,
            builder = builder,
        )
    },
    block = block,
)

internal suspend inline fun <R> withBedrockRuntimeClient(
    clientFactory: () -> BedrockRuntimeClient,
    block: suspend (BedrockRuntimeClient) -> R,
): R = clientFactory().useSafe { client ->
    block(client)
}
