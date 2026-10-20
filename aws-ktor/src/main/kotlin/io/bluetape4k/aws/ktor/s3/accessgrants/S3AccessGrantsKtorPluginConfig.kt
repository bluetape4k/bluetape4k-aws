package io.bluetape4k.aws.ktor.s3.accessgrants

import aws.sdk.kotlin.services.s3control.S3ControlClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorS3ControlClientCustomizer

/**
 * [S3AccessGrantsKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업만 등록합니다. 애플리케이션 코드가
 * [S3AccessGrantsKtorOperations]를 호출할 때만 Access Grants를 호출합니다.
 */
class S3AccessGrantsKtorPluginConfig {

    /** Ktor S3 Access Grants 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS Kotlin SDK S3 Control 클라이언트입니다. */
    var s3ControlClient: S3ControlClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var s3AccessGrantsOperations: S3AccessGrantsKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 S3 Control 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 S3 Control 엔드포인트 재정의입니다. */
    var endpointOverride: Url? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: CredentialsProvider? = null

    /** 플러그인이 생성할 클라이언트에 전달할 호출자 소유 Smithy HTTP 엔진입니다. */
    var httpClient: HttpClientEngine? = null

    private val clientCustomizers = mutableListOf<AwsKtorS3ControlClientCustomizer>()

    /** 플러그인이 생성한 클라이언트에 S3 Control builder 사용자 정의 설정을 추가합니다. */
    fun s3ControlClient(customizer: AwsKtorS3ControlClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): S3AccessGrantsKtorRuntime? {
        if (!enabled) {
            return null
        }

        s3AccessGrantsOperations?.let { return S3AccessGrantsKtorRuntime(it) }

        val injectedClient = s3ControlClient
        val client = injectedClient ?: createS3ControlClient(defaults)
        val operations = S3AccessGrantsKtorTemplate(client)

        return S3AccessGrantsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createS3ControlClient(defaults: AwsKtorDefaults): S3ControlClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.endpointOverride?.let { Url.parse(it.toString()) }
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        return S3ControlClient {
            effectiveRegion?.let { region = it }
            effectiveEndpoint?.let { endpointUrl = it }
            (credentialsProvider ?: defaults.kotlinCredentialsProvider)?.let { credentialsProvider = it }
            (httpClient ?: defaults.kotlinHttpClient)?.let { httpClient = it }
            defaults.s3ControlClientCustomizers.forEach { it.customize(this) }
            clientCustomizers.forEach { it.customize(this) }
        }
    }

    override fun toString(): String =
        ToStringBuilder(this)
            .add("enabled", enabled)
            .add("s3ControlClientConfigured", s3ControlClient != null)
            .add("s3AccessGrantsOperationsConfigured", s3AccessGrantsOperations != null)
            .add("clientCustomizersCount", clientCustomizers.size)
            .add("region", region)
            .add("endpointConfigured", endpointOverride != null)
            .add("credentialsProviderConfigured", credentialsProvider != null)
            .toString()
}
