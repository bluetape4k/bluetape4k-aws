package io.bluetape4k.aws.ssm

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmAsyncClient
import software.amazon.awssdk.services.ssm.SsmAsyncClientBuilder
import java.net.URI

/**
 * Builds an [SsmAsyncClient] and registers it with [ShutdownQueue].
 */
inline fun ssmAsyncClient(builder: SsmAsyncClientBuilder.() -> Unit): SsmAsyncClient =
    SsmAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates an [SsmAsyncClient] with optional local endpoint and credentials.
 */
inline fun ssmAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: SsmAsyncClientBuilder.() -> Unit = {},
): SsmAsyncClient = ssmAsyncClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
