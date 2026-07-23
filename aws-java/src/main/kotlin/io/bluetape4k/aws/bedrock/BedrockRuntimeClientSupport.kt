package io.bluetape4k.aws.bedrock

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder
import java.net.URI

/**
 * Verifies that a Bedrock endpoint uses HTTPS or literal loopback HTTP.
 *
 * Host names are not resolved. Plain HTTP is limited to local emulator tests.
 */
@PublishedApi
internal fun URI.requireTrustedBedrockEndpoint(): URI = apply {
    val normalizedHost = host
        ?.lowercase()
        ?.removePrefix("[")
        ?.removeSuffix("]")
    require(!normalizedHost.isNullOrBlank()) {
        "Bedrock endpoint must include a host."
    }
    val isLoopback = normalizedHost == "localhost" ||
        normalizedHost == "127.0.0.1" ||
        normalizedHost == "::1"
    require(
        scheme.equals("https", ignoreCase = true) ||
            (scheme.equals("http", ignoreCase = true) && isLoopback),
    ) {
        "Bedrock endpoint must use HTTPS; plain HTTP is allowed only for literal loopback tests."
    }
}

/**
 * Builds an AWS SDK v2 [BedrockRuntimeClient].
 *
 * The final endpoint must use HTTPS except for literal loopback HTTP. The
 * caller owns the returned client and may close it early; it also remains
 * registered with [ShutdownQueue] as a lifecycle fallback. The application
 * must add `software.amazon.awssdk:bedrockruntime` at runtime.
 */
inline fun bedrockRuntimeClient(
    builder: BedrockRuntimeClientBuilder.() -> Unit,
): BedrockRuntimeClient {
    val client = BedrockRuntimeClient.builder().apply(builder).build()
    try {
        client.serviceClientConfiguration()
            .endpointOverride()
            .orElse(null)
            ?.requireTrustedBedrockEndpoint()
    } catch (cause: Throwable) {
        try {
            client.close()
        } finally {
            throw cause
        }
    }
    return client.apply { ShutdownQueue.register(this) }
}

/**
 * Builds a caller-owned [BedrockRuntimeClient] with optional AWS settings.
 *
 * Explicit parameters are helper-owned and take precedence over [builder].
 * HTTPS is required except for literal loopback HTTP used by local tests.
 * The caller may close the client early, and the application must add
 * `software.amazon.awssdk:bedrockruntime` at runtime.
 */
inline fun bedrockRuntimeClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: BedrockRuntimeClientBuilder.() -> Unit = {},
): BedrockRuntimeClient = bedrockRuntimeClient {
    builder()
    endpoint?.requireTrustedBedrockEndpoint()?.let(::endpointOverride)
    region?.let(::region)
    credentialsProvider?.let(::credentialsProvider)
    httpClient(httpClient)
}
