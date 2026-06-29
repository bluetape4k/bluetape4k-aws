package io.bluetape4k.aws.secretsmanager

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder
import java.net.URI

/**
 * Builds a [SecretsManagerClient] and registers it with [ShutdownQueue].
 */
inline fun secretsManagerClient(
    builder: SecretsManagerClientBuilder.() -> Unit,
): SecretsManagerClient =
    SecretsManagerClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [SecretsManagerClient] for [region].
 */
inline fun secretsManagerClientOf(
    region: Region,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: SecretsManagerClientBuilder.() -> Unit = {},
): SecretsManagerClient = secretsManagerClient {
    region(region)
    httpClient(httpClient)

    builder()
}

/**
 * Creates a [SecretsManagerClient] with optional local endpoint and credentials.
 */
inline fun secretsManagerClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: SecretsManagerClientBuilder.() -> Unit = {},
): SecretsManagerClient = secretsManagerClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
