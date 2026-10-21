package io.bluetape4k.aws.ktor.eventbridge

import aws.sdk.kotlin.services.eventbridge.endpoints.DefaultEventBridgeEndpointProvider
import aws.sdk.kotlin.services.eventbridge.endpoints.EventBridgeEndpointProvider
import io.bluetape4k.aws.ktor.AwsKotlinEndpointPolicyInterceptor
import io.bluetape4k.aws.ktor.checkedAwsKotlinEndpointProvider
import io.bluetape4k.aws.ktor.requireAwsKotlinEndpointProviderUnset
import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.aws.kotlin.eventbridge.eventBridgeClientOf
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorEventBridgeClientCustomizer
import io.bluetape4k.support.requireNotBlank

/**
 * [EventBridgeKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업만 등록합니다. 애플리케이션 코드가
 * [EventBridgeKtorOperations]를 호출할 때만 이벤트를 전송합니다.
 * 주입된 client에는 플러그인의 endpoint 정책 검사가 적용되지 않으므로 호출자가 전송 endpoint를 보호해야 합니다.
 */
class EventBridgeKtorPluginConfig {

    /** Ktor EventBridge 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS Kotlin SDK EventBridge 클라이언트입니다. */
    var eventBridgeClient: EventBridgeClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var eventBridgeOperations: EventBridgeKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 EventBridge 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 EventBridge 엔드포인트 재정의입니다. */
    var endpointOverride: Url? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: CredentialsProvider? = null

    /** 플러그인이 생성할 클라이언트에 전달할 호출자 소유 Smithy HTTP 엔진입니다. */
    var httpClient: HttpClientEngine? = null

    /** 이벤트 버스를 생략한 규칙, 대상, 목록 작업에 사용할 기본 이벤트 버스입니다. */
    var defaultEventBusName: String? = null

    private val clientCustomizers = mutableListOf<AwsKtorEventBridgeClientCustomizer>()

    /**
     * 플러그인이 생성한 클라이언트에 EventBridge builder 사용자 정의 설정을 추가합니다.
     * 동적 endpoint provider는 허용되지 않습니다. 고정 endpoint에는 [endpointOverride]를 사용합니다.
     */
    fun eventBridgeClient(customizer: AwsKtorEventBridgeClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): EventBridgeKtorRuntime? {
        if (!enabled) {
            return null
        }

        eventBridgeOperations?.let { return EventBridgeKtorRuntime(it) }

        defaultEventBusName?.requireNotBlank("defaultEventBusName")

        val injectedClient = eventBridgeClient
        val client = injectedClient ?: createEventBridgeClient(defaults)
        val operations = EventBridgeKtorTemplate(
            eventBridgeClient = client,
            defaultEventBusName = defaultEventBusName,
        )

        return EventBridgeKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createEventBridgeClient(defaults: AwsKtorDefaults): EventBridgeClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.endpointOverride?.let { Url.parse(it.toString()) }
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        return eventBridgeClientOf(
            endpointUrl = effectiveEndpoint,
            region = effectiveRegion,
            credentialsProvider = credentialsProvider ?: defaults.kotlinCredentialsProvider,
            httpClient = httpClient ?: defaults.kotlinHttpClient,
            builder = {
                defaults.eventBridgeClientCustomizers.forEach { it.customize(this) }
                clientCustomizers.forEach { it.customize(this) }
                requireAwsKotlinEndpointProviderUnset(endpointProvider)
                endpointProvider = checkedAwsKotlinEndpointProvider(
                    provider = endpointProvider ?: DefaultEventBridgeEndpointProvider(),
                    region = { it.region },
                    allowInsecureLocalHttpEndpoint = defaults.allowInsecureLocalHttpEndpoint,
                    create = { resolver -> EventBridgeEndpointProvider { parameters -> resolver(parameters) } },
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
            .add("eventBridgeClientConfigured", eventBridgeClient != null)
            .add("eventBridgeOperationsConfigured", eventBridgeOperations != null)
            .add("clientCustomizersCount", clientCustomizers.size)
            .add("region", region)
            .add("endpointConfigured", endpointOverride != null)
            .add("credentialsProviderConfigured", credentialsProvider != null)
            .add("defaultEventBusName", defaultEventBusName)
            .toString()
}
