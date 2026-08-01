package io.bluetape4k.aws.ktor.ses

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorSesV2AsyncClientCustomizer
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient
import java.net.URI

/**
 * [SesKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업만 등록합니다. 애플리케이션 코드가 [SesKtorOperations]를
 * 호출하기 전에는 이메일을 전송하지 않습니다.
 */
class SesKtorPluginConfig {

    /** Ktor SES 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS SDK v2 SES v2 비동기 클라이언트입니다. */
    var sesV2AsyncClient: SesV2AsyncClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var sesOperations: SesKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 SES 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 SES 엔드포인트 재정의입니다. */
    var endpointOverride: URI? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** 요청에서 `from`을 생략했을 때 사용하는 기본 발신자입니다. */
    var defaultFrom: String? = null

    /** 요청에서 구성 집합을 생략했을 때 사용하는 기본 SES 구성 집합입니다. */
    var configurationSetName: String? = null

    private val clientCustomizers = mutableListOf<AwsKtorSesV2AsyncClientCustomizer>()

    /**
     * 플러그인이 생성한 클라이언트에 SES v2 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun sesV2AsyncClient(customizer: AwsKtorSesV2AsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): SesKtorRuntime? {
        if (!enabled) {
            return null
        }

        sesOperations?.let { return SesKtorRuntime(it) }

        defaultFrom?.requireEmailHeaderValue("defaultFrom")
        configurationSetName?.let { require(it.isNotBlank()) { "configurationSetName must not be blank." } }

        val injectedClient = sesV2AsyncClient
        val client = injectedClient ?: createSesV2AsyncClient(defaults)
        val operations = SesKtorTemplate(
            sesAsyncClient = client,
            defaultFrom = defaultFrom,
            configurationSetName = configurationSetName,
        )

        return SesKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createSesV2AsyncClient(defaults: AwsKtorDefaults): SesV2AsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = SesV2AsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.sesV2AsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
