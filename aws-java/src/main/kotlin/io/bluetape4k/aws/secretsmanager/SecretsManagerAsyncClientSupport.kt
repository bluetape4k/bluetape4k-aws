package io.bluetape4k.aws.secretsmanager

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClientBuilder
import java.net.URI

/**
 * [SecretsManagerAsyncClient]를 생성하고 [ShutdownQueue]에 등록합니다.
 */
inline fun secretsManagerAsyncClient(
    builder: SecretsManagerAsyncClientBuilder.() -> Unit,
): SecretsManagerAsyncClient =
    SecretsManagerAsyncClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * 선택적인 로컬 엔드포인트와 자격 증명으로 [SecretsManagerAsyncClient]를 생성합니다.
 */
inline fun secretsManagerAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    builder: SecretsManagerAsyncClientBuilder.() -> Unit = {},
): SecretsManagerAsyncClient = secretsManagerAsyncClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
