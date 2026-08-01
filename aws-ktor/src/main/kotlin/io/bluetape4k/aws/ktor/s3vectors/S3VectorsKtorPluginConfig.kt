package io.bluetape4k.aws.ktor.s3vectors

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorS3VectorsAsyncClientCustomizer
import io.bluetape4k.aws.s3vectors.S3VectorsCoroutinesTemplate
import io.bluetape4k.aws.s3vectors.S3VectorsOperations
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient
import java.net.URI

/**
 * [S3VectorsKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업만 등록합니다. 애플리케이션 코드가 [S3VectorsOperations]를
 * 호출할 때만 S3 Vectors를 호출합니다.
 */
class S3VectorsKtorPluginConfig {

    /** Ktor S3 Vectors 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS SDK v2 S3 Vectors 비동기 클라이언트입니다. */
    var s3VectorsAsyncClient: S3VectorsAsyncClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var s3VectorsOperations: S3VectorsOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 S3 Vectors 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 S3 Vectors 엔드포인트 재정의입니다. */
    var endpointOverride: URI? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: AwsCredentialsProvider? = null

    private val clientCustomizers = mutableListOf<AwsKtorS3VectorsAsyncClientCustomizer>()

    /**
     * 플러그인이 생성한 클라이언트에 S3 Vectors 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun s3VectorsAsyncClient(customizer: AwsKtorS3VectorsAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): S3VectorsKtorRuntime? {
        if (!enabled) {
            return null
        }

        s3VectorsOperations?.let { return S3VectorsKtorRuntime(it) }

        val injectedClient = s3VectorsAsyncClient
        val client = injectedClient ?: createS3VectorsAsyncClient(defaults)
        val operations = S3VectorsCoroutinesTemplate(client)

        return S3VectorsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createS3VectorsAsyncClient(defaults: AwsKtorDefaults): S3VectorsAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = S3VectorsAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.s3VectorsAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
