package io.bluetape4k.aws.ssm

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.SsmClientBuilder
import java.net.URI

/**
 * [SsmClient]를 생성하고 [ShutdownQueue]에 등록합니다.
 */
inline fun ssmClient(builder: SsmClientBuilder.() -> Unit): SsmClient =
    SsmClient.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * [region]용 [SsmClient]를 생성합니다.
 */
inline fun ssmClientOf(
    region: Region,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: SsmClientBuilder.() -> Unit = {},
): SsmClient = ssmClient {
    region(region)
    httpClient(httpClient)

    builder()
}

/**
 * 선택적인 로컬 엔드포인트와 자격 증명으로 [SsmClient]를 생성합니다.
 */
inline fun ssmClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: SsmClientBuilder.() -> Unit = {},
): SsmClient = ssmClient {
    endpoint?.let { endpointOverride(it) }
    region?.let { region(it) }
    credentialsProvider?.let { credentialsProvider(it) }
    httpClient(httpClient)

    builder()
}
