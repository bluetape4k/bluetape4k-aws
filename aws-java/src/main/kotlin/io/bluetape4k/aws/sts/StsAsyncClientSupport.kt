package io.bluetape4k.aws.sts

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.StsAsyncClient
import software.amazon.awssdk.services.sts.StsAsyncClientBuilder
import java.net.URI

/**
 * [StsAsyncClient]를 DSL block으로 생성한다.
 *
 * ```kotlin
 * val client = stsAsyncClient { region(Region.AP_NORTHEAST_2) }
 * // client == StsAsyncClient instance
 * ```
 */
inline fun stsAsyncClient(
    builder: StsAsyncClientBuilder.() -> Unit,
): StsAsyncClient =
    StsAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * endpoint와 credential 설정으로 [StsAsyncClient]를 생성한다.
 *
 * nullable parameter는 null이 아닐 때만 builder에 반영된다.
 *
 * ```kotlin
 * val client = stsAsyncClientOf(endpoint = URI("http://localhost:4566"))
 * // client == StsAsyncClient instance
 * ```
 */
inline fun stsAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: StsAsyncClientBuilder.() -> Unit = {},
): StsAsyncClient = stsAsyncClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
