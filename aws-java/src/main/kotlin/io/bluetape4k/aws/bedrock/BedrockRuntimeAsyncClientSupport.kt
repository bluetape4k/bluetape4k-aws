package io.bluetape4k.aws.bedrock

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClientBuilder
import java.net.URI

/**
 * Builds an AWS SDK v2 [BedrockRuntimeAsyncClient].
 *
 * The final endpoint must use HTTPS except for literal loopback HTTP. The
 * caller owns the returned client and may close it early; it also remains
 * registered with [ShutdownQueue] as a lifecycle fallback. The application
 * must add `software.amazon.awssdk:bedrockruntime` at runtime.
 */
inline fun bedrockRuntimeAsyncClient(
    builder: BedrockRuntimeAsyncClientBuilder.() -> Unit,
): BedrockRuntimeAsyncClient {
    val client = BedrockRuntimeAsyncClient.builder().apply(builder).build()
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
 * Builds a caller-owned [BedrockRuntimeAsyncClient] with optional AWS settings.
 *
 * Explicit parameters are helper-owned and take precedence over [builder].
 * HTTPS is required except for literal loopback HTTP used by local tests.
 * The caller may close the client early, and the application must add
 * `software.amazon.awssdk:bedrockruntime` at runtime.
 */
inline fun bedrockRuntimeAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: BedrockRuntimeAsyncClientBuilder.() -> Unit = {},
): BedrockRuntimeAsyncClient = bedrockRuntimeAsyncClient {
    builder()
    endpoint?.requireTrustedBedrockEndpoint()?.let(::endpointOverride)
    region?.let(::region)
    credentialsProvider?.let(::credentialsProvider)
    httpClient(httpClient)
}
