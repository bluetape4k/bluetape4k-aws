package io.bluetape4k.aws.ktor.sts

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorStsAsyncClientCustomizer
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.StsAsyncClient
import java.net.URI

/**
 * [StsKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인 설치는 작업만 등록합니다. STS 자격 또는 세션 호출은 애플리케이션 코드가
 * [StsKtorOperations]를 호출할 때만 발생합니다.
 */
class StsKtorPluginConfig {

/** Ktor STS 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

/** 애플리케이션이 소유하는 선택적인 AWS SDK v2 STS 비동기 클라이언트입니다. */
    var stsAsyncClient: StsAsyncClient? = null

/** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var stsOperations: StsKtorOperations? = null

/** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 STS 리전입니다. */
    var region: String? = null

/** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 STS 엔드포인트 재정의입니다. */
    var endpointOverride: URI? = null

/** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: AwsCredentialsProvider? = null

    private val clientCustomizers = mutableListOf<AwsKtorStsAsyncClientCustomizer>()

    /**
     * 플러그인이 생성한 클라이언트에 STS 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun stsAsyncClient(customizer: AwsKtorStsAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): StsKtorRuntime? {
        if (!enabled) {
            return null
        }

        stsOperations?.let { return StsKtorRuntime(it) }

        val injectedClient = stsAsyncClient
        val client = injectedClient ?: createStsAsyncClient(defaults)
        val operations = StsKtorTemplate(client)

        return StsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createStsAsyncClient(defaults: AwsKtorDefaults): StsAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = StsAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.stsAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
