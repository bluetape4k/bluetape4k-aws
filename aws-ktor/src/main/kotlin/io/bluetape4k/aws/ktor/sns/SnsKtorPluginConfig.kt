package io.bluetape4k.aws.ktor.sns

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorSnsAsyncClientCustomizer
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import java.io.Serializable
import java.net.URI

/**
 * [SnsKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업과 SNS HTTP 파서만 등록합니다. 애플리케이션 코드가
 * [SnsKtorOperations]를 호출하기 전에는 주제를 생성하거나 메시지를 게시하거나 구독을 확인하지 않습니다.
 */
class SnsKtorPluginConfig {

    /** Ktor SNS 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS SDK v2 SNS 비동기 클라이언트입니다. */
    var snsAsyncClient: SnsAsyncClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var snsOperations: SnsKtorOperations? = null

    /** 애플리케이션이 소유하는 선택적인 SNS HTTP 메시지 파서입니다. */
    var snsHttpMessageParser: SnsHttpMessageParser? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 SNS 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 SNS 엔드포인트 재정의입니다. */
    var endpointOverride: URI? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** [SnsKtorOperations.createConfiguredTopic]에서 사용할 주제 정의입니다. */
    var topics: Map<String, SnsKtorTopic> = emptyMap()

    private val clientCustomizers = mutableListOf<AwsKtorSnsAsyncClientCustomizer>()

    /**
     * 플러그인이 생성한 클라이언트에 SNS 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun snsAsyncClient(customizer: AwsKtorSnsAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): SnsKtorRuntime? {
        if (!enabled) {
            return null
        }

        val parser = snsHttpMessageParser ?: SnsHttpMessageParser.default()
        snsOperations?.let { return SnsKtorRuntime(operations = it, parser = parser) }

        val injectedClient = snsAsyncClient
        val client = injectedClient ?: createSnsAsyncClient(defaults)
        val operations = SnsKtorTemplate(client, topics)

        return SnsKtorRuntime(
            operations = operations,
            parser = parser,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createSnsAsyncClient(defaults: AwsKtorDefaults): SnsAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = SnsAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.snsAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}

/**
 * 구성 기반 SNS 주제 생성에 사용하는 주제 속성입니다.
 */
data class SnsKtorTopic(
    val fifo: Boolean = false,
    val contentBasedDeduplication: Boolean = true,
    val fifoThroughputScope: SnsFifoThroughputScope? = null,
    val attributes: Map<String, String> = emptyMap(),
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -5060828396544333540L
    }
}
