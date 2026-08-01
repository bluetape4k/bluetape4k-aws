package io.bluetape4k.aws.ktor.imds

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireZeroOrPositiveNumber
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import software.amazon.awssdk.imds.Ec2MetadataRetryPolicy
import software.amazon.awssdk.imds.EndpointMode
import java.net.URI
import java.time.Duration

/**
 * Ktor IMDS 플러그인 구성입니다.
 *
 * ## 계약
 *
 * 플러그인 설치는 IMDS를 호출하지 않습니다. 메타데이터 요청은 [ImdsKtorOperations] 메서드를 통해서만
 * 발생하며 [requestTimeout]으로 제한됩니다.
 */
class ImdsKtorPluginConfig {

    /** Ktor IMDS 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS SDK v2 IMDS 비동기 클라이언트입니다. */
    var ec2MetadataAsyncClient: Ec2MetadataAsyncClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var imdsOperations: ImdsKtorOperations? = null

    /** 선택적인 메타데이터 엔드포인트 재정의입니다. 테스트나 사용자 정의 EC2 메타데이터 라우팅에만 사용하세요. */
    var endpoint: URI? = null

    /** [endpoint]를 지정하지 않았을 때 사용하는 IMDS 엔드포인트 모드입니다. */
    var endpointMode: EndpointMode? = EndpointMode.IPV4

    /** IMDSv2 토큰 TTL입니다. */
    var tokenTtl: Duration = Duration.ofHours(6)

    /** 각 메타데이터 작업에 적용하는 타임아웃입니다. */
    var requestTimeout: Duration = Duration.ofSeconds(1)

    /** SDK 수준 IMDS 재시도 횟수입니다. 0이면 SDK 재시도를 비활성화합니다. */
    var retries: Int = 0

    /** 플러그인이 생성하는 IMDS 클라이언트용 선택적인 비동기 HTTP 클라이언트입니다. */
    var httpClient: SdkAsyncHttpClient? = null

    private val clientCustomizers = mutableListOf<ImdsKtorClientCustomizer>()

    /**
     * 플러그인이 생성한 클라이언트에 IMDS 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun client(customizer: ImdsKtorClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(): ImdsKtorRuntime? {
        if (!enabled) {
            return null
        }

        imdsOperations?.let { return ImdsKtorRuntime(it) }

        validate()

        val injectedClient = ec2MetadataAsyncClient
        val client = injectedClient ?: createEc2MetadataAsyncClient()
        val operations = ImdsKtorTemplate(client, requestTimeout)

        return ImdsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun validate() {
        tokenTtl.requireGt(Duration.ZERO, "tokenTtl")
        requestTimeout.requireGt(Duration.ZERO, "requestTimeout")
        retries.requireZeroOrPositiveNumber("retries")
    }

    private fun createEc2MetadataAsyncClient(): Ec2MetadataAsyncClient =
        Ec2MetadataAsyncClient.builder()
            .tokenTtl(tokenTtl)
            .retryPolicy(resolveRetryPolicy())
            .httpClient(httpClient ?: SdkAsyncHttpClientProvider.defaultHttpClient)
            .apply {
                endpoint?.let { endpoint(it) } ?: endpointMode?.let { endpointMode(it) }
                clientCustomizers.forEach { it.customize(this) }
            }
            .build()

    private fun resolveRetryPolicy(): Ec2MetadataRetryPolicy =
        if (retries == 0) {
            Ec2MetadataRetryPolicy.none()
        } else {
            Ec2MetadataRetryPolicy.builder()
                .numRetries(retries)
                .build()
        }
}

/**
 * 플러그인이 생성하는 AWS SDK v2 IMDS 비동기 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface ImdsKtorClientCustomizer {
    fun customize(builder: Ec2MetadataAsyncClient.Builder)
}
