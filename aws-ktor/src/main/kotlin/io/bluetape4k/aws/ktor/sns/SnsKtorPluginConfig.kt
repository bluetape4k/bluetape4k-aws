package io.bluetape4k.aws.ktor.sns

import aws.sdk.kotlin.services.sns.SnsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorSnsClientCustomizer
import io.bluetape4k.aws.kotlin.sns.snsClientOf
import java.io.Serializable

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

    /** 애플리케이션이 소유하는 선택적인 AWS Kotlin SDK SNS 클라이언트입니다. */
    var snsClient: SnsClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var snsOperations: SnsKtorOperations? = null

    /** 애플리케이션이 소유하는 선택적인 SNS HTTP 메시지 파서입니다. */
    var snsHttpMessageParser: SnsHttpMessageParser? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 SNS 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 SNS 엔드포인트 재정의입니다. */
    var endpointOverride: Url? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: CredentialsProvider? = null

    /** 플러그인이 생성한 클라이언트에 사용할 호출자 소유 Smithy HTTP 엔진입니다. */
    var httpClient: HttpClientEngine? = null

    /** [SnsKtorOperations.createConfiguredTopic]에서 사용할 주제 정의입니다. */
    var topics: Map<String, SnsKtorTopic> = emptyMap()

    private val clientCustomizers = mutableListOf<AwsKtorSnsClientCustomizer>()

    /** 플러그인이 생성한 AWS Kotlin SNS 클라이언트에 사용자 정의 설정을 추가합니다. */
    fun snsClient(customizer: AwsKtorSnsClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): SnsKtorRuntime? {
        if (!enabled) return null

        val parser = snsHttpMessageParser ?: SnsHttpMessageParser.default()
        snsOperations?.let { return SnsKtorRuntime(operations = it, parser = parser) }

        val injectedClient = snsClient
        val client = injectedClient ?: createSnsClient(defaults)
        val operations = SnsKtorTemplate(client, topics)

        return SnsKtorRuntime(
            operations = operations,
            parser = parser,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createSnsClient(defaults: AwsKtorDefaults): SnsClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.endpointOverride?.let { Url.parse(it.toString()) }
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        return snsClientOf(
            endpointUrl = effectiveEndpoint,
            region = effectiveRegion,
            credentialsProvider = credentialsProvider ?: defaults.kotlinCredentialsProvider,
            httpClient = httpClient ?: defaults.kotlinHttpClient,
            builder = {
                defaults.snsClientCustomizers.forEach { it.customize(this) }
                clientCustomizers.forEach { it.customize(this) }
            },
        )
    }

    override fun toString(): String =
        ToStringBuilder(this)
            .add("enabled", enabled)
            .add("snsClientConfigured", snsClient != null)
            .add("snsOperationsConfigured", snsOperations != null)
            .add("snsHttpMessageParserConfigured", snsHttpMessageParser != null)
            .add("region", region)
            .add("endpointConfigured", endpointOverride != null)
            .add("credentialsProviderConfigured", credentialsProvider != null)
            .add("httpClientConfigured", httpClient != null)
            .add("topicsCount", topics.size)
            .toString()
}

/** 구성 기반 SNS 주제 생성에 사용하는 주제 속성입니다. */
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
