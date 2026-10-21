package io.bluetape4k.aws.ktor.sts

import aws.sdk.kotlin.services.sts.endpoints.DefaultStsEndpointProvider
import aws.sdk.kotlin.services.sts.endpoints.StsEndpointProvider
import io.bluetape4k.aws.ktor.AwsKotlinEndpointPolicyInterceptor
import io.bluetape4k.aws.ktor.checkedAwsKotlinEndpointProvider
import io.bluetape4k.aws.ktor.requireAwsKotlinEndpointProviderUnset
import aws.sdk.kotlin.services.sts.StsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorStsClientCustomizer
import io.bluetape4k.aws.kotlin.sts.stsClientOf

/**
 * [StsKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인 설치는 작업만 등록합니다. STS 자격 또는 세션 호출은 애플리케이션 코드가
 * [StsKtorOperations]를 호출할 때만 발생합니다.
 * 주입된 client에는 플러그인의 endpoint 정책 검사가 적용되지 않으므로 호출자가 전송 endpoint를 보호해야 합니다.
 */
class StsKtorPluginConfig {

    /** Ktor STS 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS Kotlin SDK STS 클라이언트입니다. */
    var stsClient: StsClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var stsOperations: StsKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 STS 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 STS 엔드포인트 재정의입니다. */
    var endpointOverride: Url? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: CredentialsProvider? = null

    /** 플러그인이 생성한 클라이언트에 사용할 호출자 소유 Smithy HTTP 엔진입니다. */
    var httpClient: HttpClientEngine? = null

    private val clientCustomizers = mutableListOf<AwsKtorStsClientCustomizer>()

    /**
     * 플러그인이 생성한 AWS Kotlin STS 클라이언트에 사용자 정의 설정을 추가합니다.
     * 동적 endpoint provider는 허용되지 않습니다. 고정 endpoint에는 [endpointOverride]를 사용합니다.
     */
    fun stsClient(customizer: AwsKtorStsClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): StsKtorRuntime? {
        if (!enabled) return null
        stsOperations?.let { return StsKtorRuntime(it) }

        val injectedClient = stsClient
        val client = injectedClient ?: createStsClient(defaults)
        val operations = StsKtorTemplate(client)

        return StsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createStsClient(defaults: AwsKtorDefaults): StsClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.endpointOverride?.let { Url.parse(it.toString()) }
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        return stsClientOf(
            endpointUrl = effectiveEndpoint,
            region = effectiveRegion,
            credentialsProvider = credentialsProvider ?: defaults.kotlinCredentialsProvider,
            httpClient = httpClient ?: defaults.kotlinHttpClient,
            builder = {
                defaults.stsClientCustomizers.forEach { it.customize(this) }
                clientCustomizers.forEach { it.customize(this) }
                requireAwsKotlinEndpointProviderUnset(endpointProvider)
                endpointProvider = checkedAwsKotlinEndpointProvider(
                    provider = endpointProvider ?: DefaultStsEndpointProvider(),
                    region = { it.region },
                    allowInsecureLocalHttpEndpoint = defaults.allowInsecureLocalHttpEndpoint,
                    create = { resolver -> StsEndpointProvider { parameters -> resolver(parameters) } },
                )
                interceptors.add(
                    AwsKotlinEndpointPolicyInterceptor(
                        configuredEndpoint = this.endpointUrl?.toString(),
                        region = this.region,
                        allowInsecureLocalHttpEndpoint = defaults.allowInsecureLocalHttpEndpoint,
                    ),
                )
            },
        )
    }

    override fun toString(): String =
        ToStringBuilder(this)
            .add("enabled", enabled)
            .add("stsClientConfigured", stsClient != null)
            .add("stsOperationsConfigured", stsOperations != null)
            .add("region", region)
            .add("endpointConfigured", endpointOverride != null)
            .add("credentialsProviderConfigured", credentialsProvider != null)
            .add("httpClientConfigured", httpClient != null)
            .toString()
}
