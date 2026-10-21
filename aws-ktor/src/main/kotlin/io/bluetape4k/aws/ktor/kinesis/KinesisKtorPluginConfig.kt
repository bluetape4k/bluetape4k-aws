package io.bluetape4k.aws.ktor.kinesis

import aws.sdk.kotlin.services.kinesis.endpoints.DefaultKinesisEndpointProvider
import aws.sdk.kotlin.services.kinesis.endpoints.KinesisEndpointProvider
import io.bluetape4k.aws.ktor.AwsKotlinEndpointPolicyInterceptor
import io.bluetape4k.aws.ktor.checkedAwsKotlinEndpointProvider
import io.bluetape4k.aws.ktor.requireAwsKotlinEndpointProviderUnset
import io.bluetape4k.ToStringBuilder
import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.aws.kotlin.kinesis.kinesisClientOf
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorKinesisClientCustomizer

/**
 * [KinesisKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업만 등록합니다. 애플리케이션 코드가 [KinesisKtorOperations]를
 * 호출할 때만 스트림을 생성하고 레코드를 게시하며 소비자 Flow를 수집합니다.
 * 주입된 client에는 플러그인의 endpoint 정책 검사가 적용되지 않으므로 호출자가 전송 endpoint를 보호해야 합니다.
 */
class KinesisKtorPluginConfig {

    /** Ktor Kinesis 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS Kotlin SDK Kinesis 클라이언트입니다. */
    var kinesisClient: KinesisClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var kinesisOperations: KinesisKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 Kinesis 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 Kinesis 엔드포인트 재정의입니다. */
    var endpointOverride: Url? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: CredentialsProvider? = null

    /** 플러그인이 생성할 클라이언트에 전달할 호출자 소유 Smithy HTTP 엔진입니다. */
    var httpClient: HttpClientEngine? = null

    /** [KinesisKtorOperations.createConfiguredStream]에서 사용할 스트림 정의입니다. */
    var streams: Map<String, KinesisKtorStream> = emptyMap()

    private val clientCustomizers = mutableListOf<AwsKtorKinesisClientCustomizer>()

    /**
     * 플러그인이 생성한 클라이언트에 Kinesis 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     * 동적 endpoint provider는 허용되지 않습니다. 고정 endpoint에는 [endpointOverride]를 사용합니다.
     */
    fun kinesisClient(customizer: AwsKtorKinesisClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): KinesisKtorRuntime? {
        if (!enabled) {
            return null
        }

        kinesisOperations?.let { return KinesisKtorRuntime(it) }

        val injectedClient = kinesisClient
        val client = injectedClient ?: createKinesisClient(defaults)
        val operations = KinesisKtorTemplate(client, streams)

        return KinesisKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createKinesisClient(defaults: AwsKtorDefaults): KinesisClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.endpointOverride?.let { Url.parse(it.toString()) }
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        return kinesisClientOf(
            endpointUrl = effectiveEndpoint,
            region = effectiveRegion,
            credentialsProvider = credentialsProvider ?: defaults.kotlinCredentialsProvider,
            httpClient = httpClient ?: defaults.kotlinHttpClient,
            builder = {
                defaults.kinesisClientCustomizers.forEach { it.customize(this) }
                clientCustomizers.forEach { it.customize(this) }
                requireAwsKotlinEndpointProviderUnset(endpointProvider)
                endpointProvider = checkedAwsKotlinEndpointProvider(
                    provider = endpointProvider ?: DefaultKinesisEndpointProvider(),
                    region = { it.region },
                    allowInsecureLocalHttpEndpoint = defaults.allowInsecureLocalHttpEndpoint,
                    create = { resolver -> KinesisEndpointProvider { parameters -> resolver(parameters) } },
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
            .add("kinesisClientConfigured", kinesisClient != null)
            .add("kinesisOperationsConfigured", kinesisOperations != null)
            .add("region", region)
            .add("endpointConfigured", endpointOverride != null)
            .add("credentialsProviderConfigured", credentialsProvider != null)
            .add("httpClientConfigured", httpClient != null)
            .add("streamsCount", streams.size)
            .toString()
}
