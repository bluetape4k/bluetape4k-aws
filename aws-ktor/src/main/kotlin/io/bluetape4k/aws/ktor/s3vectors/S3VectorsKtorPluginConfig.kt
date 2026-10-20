package io.bluetape4k.aws.ktor.s3vectors

import aws.sdk.kotlin.services.s3vectors.S3VectorsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorS3VectorsClientCustomizer

/**
 * [S3VectorsKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업만 등록합니다. 애플리케이션 코드가
 * [S3VectorsKtorOperations]를 호출할 때만 S3 Vectors를 호출합니다.
 */
class S3VectorsKtorPluginConfig {

    /** Ktor S3 Vectors 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS Kotlin SDK S3 Vectors 클라이언트입니다. */
    var s3VectorsClient: S3VectorsClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var s3VectorsOperations: S3VectorsKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 S3 Vectors 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 S3 Vectors 엔드포인트 재정의입니다. */
    var endpointOverride: Url? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: CredentialsProvider? = null

    /** 플러그인이 생성할 클라이언트에 전달할 호출자 소유 Smithy HTTP 엔진입니다. */
    var httpClient: HttpClientEngine? = null

    private val clientCustomizers = mutableListOf<AwsKtorS3VectorsClientCustomizer>()

    /** 플러그인이 생성한 클라이언트에 S3 Vectors builder 사용자 정의 설정을 추가합니다. */
    fun s3VectorsClient(customizer: AwsKtorS3VectorsClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): S3VectorsKtorRuntime? {
        if (!enabled) {
            return null
        }

        s3VectorsOperations?.let { return S3VectorsKtorRuntime(it) }

        val injectedClient = s3VectorsClient
        val client = injectedClient ?: createS3VectorsClient(defaults)
        val operations = S3VectorsKtorTemplate(client)

        return S3VectorsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createS3VectorsClient(defaults: AwsKtorDefaults): S3VectorsClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.endpointOverride?.let { Url.parse(it.toString()) }
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        return S3VectorsClient {
            effectiveRegion?.let { region = it }
            effectiveEndpoint?.let { endpointUrl = it }
            (credentialsProvider ?: defaults.kotlinCredentialsProvider)?.let { credentialsProvider = it }
            (httpClient ?: defaults.kotlinHttpClient)?.let { httpClient = it }
            defaults.s3VectorsClientCustomizers.forEach { it.customize(this) }
            clientCustomizers.forEach { it.customize(this) }
        }
    }

    override fun toString(): String =
        ToStringBuilder(this)
            .add("enabled", enabled)
            .add("s3VectorsClientConfigured", s3VectorsClient != null)
            .add("s3VectorsOperationsConfigured", s3VectorsOperations != null)
            .add("clientCustomizersCount", clientCustomizers.size)
            .add("region", region)
            .add("endpointConfigured", endpointOverride != null)
            .add("credentialsProviderConfigured", credentialsProvider != null)
            .toString()
}
