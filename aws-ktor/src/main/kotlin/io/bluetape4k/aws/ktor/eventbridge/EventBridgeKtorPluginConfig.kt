package io.bluetape4k.aws.ktor.eventbridge

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorEventBridgeAsyncClientCustomizer
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import java.net.URI

/**
 * [EventBridgeKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업만 등록합니다. 애플리케이션 코드가
 * [EventBridgeKtorOperations]를 호출할 때만 이벤트를 전송합니다.
 */
class EventBridgeKtorPluginConfig {

    /** Ktor EventBridge 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS SDK v2 EventBridge 비동기 클라이언트입니다. */
    var eventBridgeAsyncClient: EventBridgeAsyncClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var eventBridgeOperations: EventBridgeKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 EventBridge 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 EventBridge 엔드포인트 재정의입니다. */
    var endpointOverride: URI? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** 이벤트 버스를 생략한 규칙, 대상, 목록 작업에 사용할 기본 이벤트 버스입니다. */
    var defaultEventBusName: String? = null

    private val clientCustomizers = mutableListOf<AwsKtorEventBridgeAsyncClientCustomizer>()

    /**
     * 플러그인이 생성한 클라이언트에 EventBridge 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun eventBridgeAsyncClient(customizer: AwsKtorEventBridgeAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): EventBridgeKtorRuntime? {
        if (!enabled) {
            return null
        }

        eventBridgeOperations?.let { return EventBridgeKtorRuntime(it) }

        defaultEventBusName?.requireNotBlank("defaultEventBusName")

        val injectedClient = eventBridgeAsyncClient
        val client = injectedClient ?: createEventBridgeAsyncClient(defaults)
        val operations = EventBridgeKtorTemplate(
            eventBridgeAsyncClient = client,
            defaultEventBusName = defaultEventBusName,
        )

        return EventBridgeKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createEventBridgeAsyncClient(defaults: AwsKtorDefaults): EventBridgeAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = EventBridgeAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.eventBridgeAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
