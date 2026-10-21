package io.bluetape4k.aws.ktor.ses

import aws.sdk.kotlin.services.sesv2.endpoints.DefaultSesV2EndpointProvider
import aws.sdk.kotlin.services.sesv2.endpoints.SesV2EndpointProvider
import io.bluetape4k.aws.ktor.AwsKotlinEndpointPolicyInterceptor
import io.bluetape4k.aws.ktor.checkedAwsKotlinEndpointProvider
import io.bluetape4k.aws.ktor.requireAwsKotlinEndpointProviderUnset
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorSesV2ClientCustomizer
import io.bluetape4k.aws.kotlin.sesv2.sesV2ClientOf
import io.bluetape4k.support.requireNotBlank
import aws.sdk.kotlin.services.sesv2.SesV2Client
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url

/**
 * [SesKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업만 등록합니다. 애플리케이션 코드가 [SesKtorOperations]를
 * 호출하기 전에는 이메일을 전송하지 않습니다.
 * 주입된 client에는 플러그인의 endpoint 정책 검사가 적용되지 않으므로 호출자가 전송 endpoint를 보호해야 합니다.
 */
class SesKtorPluginConfig {

    /** Ktor SES 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS Kotlin SDK SES v2 클라이언트입니다. */
    var sesV2Client: SesV2Client? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var sesOperations: SesKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 SES 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 SES 엔드포인트 재정의입니다. */
    var endpointOverride: Url? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: CredentialsProvider? = null

    /** 플러그인이 생성한 클라이언트에 사용할 호출자 소유 Smithy HTTP 엔진입니다. */
    var httpClient: HttpClientEngine? = null

    /** 요청에서 `from`을 생략했을 때 사용하는 기본 발신자입니다. */
    var defaultFrom: String? = null

    /** 요청에서 구성 집합을 생략했을 때 사용하는 기본 SES 구성 집합입니다. */
    var configurationSetName: String? = null

    private val clientCustomizers = mutableListOf<AwsKtorSesV2ClientCustomizer>()

    /**
     * 플러그인이 생성한 AWS Kotlin SES v2 클라이언트에 사용자 정의 설정을 추가합니다.
     * 동적 endpoint provider는 허용되지 않습니다. 고정 endpoint에는 [endpointOverride]를 사용합니다.
     */
    fun sesV2Client(customizer: AwsKtorSesV2ClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): SesKtorRuntime? {
        if (!enabled) {
            return null
        }

        sesOperations?.let { return SesKtorRuntime(it) }

        defaultFrom?.requireEmailHeaderValue("defaultFrom")
        configurationSetName?.requireNotBlank("configurationSetName")

        val injectedClient = sesV2Client
        val client = injectedClient ?: createSesV2Client(defaults)
        val operations = SesKtorTemplate(
            sesClient = client,
            defaultFrom = defaultFrom,
            configurationSetName = configurationSetName,
        )

        return SesKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createSesV2Client(defaults: AwsKtorDefaults): SesV2Client {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.endpointOverride?.let { Url.parse(it.toString()) }
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        return sesV2ClientOf(
            endpointUrl = effectiveEndpoint,
            region = effectiveRegion,
            credentialsProvider = credentialsProvider ?: defaults.kotlinCredentialsProvider,
            httpClient = httpClient ?: defaults.kotlinHttpClient,
            builder = {
                defaults.sesV2ClientCustomizers.forEach { it.customize(this) }
                clientCustomizers.forEach { it.customize(this) }
                requireAwsKotlinEndpointProviderUnset(endpointProvider)
                endpointProvider = checkedAwsKotlinEndpointProvider(
                    provider = endpointProvider ?: DefaultSesV2EndpointProvider(),
                    region = { it.region },
                    allowInsecureLocalHttpEndpoint = defaults.allowInsecureLocalHttpEndpoint,
                    create = { resolver -> SesV2EndpointProvider { parameters -> resolver(parameters) } },
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
            .add("sesV2ClientConfigured", sesV2Client != null)
            .add("sesOperationsConfigured", sesOperations != null)
            .add("region", region)
            .add("endpointConfigured", endpointOverride != null)
            .add("credentialsProviderConfigured", credentialsProvider != null)
            .add("httpClientConfigured", httpClient != null)
            .add("defaultFrom", defaultFrom)
            .add("configurationSetName", configurationSetName)
            .toString()
}
