package io.bluetape4k.aws.ktor.kinesis

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorKinesisAsyncClientCustomizer
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import java.net.URI

/**
 * [KinesisKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업만 등록합니다. 애플리케이션 코드가 [KinesisKtorOperations]를
 * 호출할 때만 스트림을 생성하고 레코드를 게시하며 소비자 Flow를 수집합니다.
 */
class KinesisKtorPluginConfig {

    /** Ktor Kinesis 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS SDK v2 Kinesis 비동기 클라이언트입니다. */
    var kinesisAsyncClient: KinesisAsyncClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var kinesisOperations: KinesisKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 Kinesis 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 Kinesis 엔드포인트 재정의입니다. */
    var endpointOverride: URI? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** [KinesisKtorOperations.createConfiguredStream]에서 사용할 스트림 정의입니다. */
    var streams: Map<String, KinesisKtorStream> = emptyMap()

    private val clientCustomizers = mutableListOf<AwsKtorKinesisAsyncClientCustomizer>()

    /**
     * 플러그인이 생성한 클라이언트에 Kinesis 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun kinesisAsyncClient(customizer: AwsKtorKinesisAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): KinesisKtorRuntime? {
        if (!enabled) {
            return null
        }

        kinesisOperations?.let { return KinesisKtorRuntime(it) }

        val injectedClient = kinesisAsyncClient
        val client = injectedClient ?: createKinesisAsyncClient(defaults)
        val operations = KinesisKtorTemplate(client, streams)

        return KinesisKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createKinesisAsyncClient(defaults: AwsKtorDefaults): KinesisAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = KinesisAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.kinesisAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
