package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.ToStringBuilder
import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.aws.kotlin.cloudwatch.cloudWatchClientOf
import io.bluetape4k.aws.ktor.AwsKtorCloudWatchClientCustomizer
import io.bluetape4k.aws.ktor.AwsKtorDefaults

/**
 * [CloudWatchKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업만 등록합니다. 애플리케이션 코드가 [CloudWatchKtorOperations]를
 * 호출하기 전에는 메트릭을 게시하지 않습니다.
 */
class CloudWatchKtorPluginConfig {

    /** Ktor CloudWatch 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS Kotlin SDK CloudWatch 클라이언트입니다. */
    var cloudWatchClient: CloudWatchClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var cloudWatchOperations: CloudWatchKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 CloudWatch 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 CloudWatch 엔드포인트 재정의입니다. */
    var endpointOverride: Url? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: CredentialsProvider? = null

    /** 플러그인이 생성할 client에 전달할 호출자 소유 Smithy HTTP 엔진입니다. */
    var httpClient: HttpClientEngine? = null

    /** 네임스페이스를 생략한 작업에 사용할 기본 CloudWatch 네임스페이스입니다. */
    var namespace: String? = null

    /** CloudWatch PutMetricData 배치 크기입니다. AWS는 1..1000을 허용합니다. */
    var batchSize: Int = CLOUDWATCH_MAX_BATCH_SIZE

    private val clientCustomizers = mutableListOf<AwsKtorCloudWatchClientCustomizer>()

    /**
     * 플러그인이 생성한 클라이언트에 CloudWatch 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun cloudWatchClient(customizer: AwsKtorCloudWatchClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): CloudWatchKtorRuntime? {
        if (!enabled) {
            return null
        }

        cloudWatchOperations?.let { return CloudWatchKtorRuntime(it) }

        val injectedClient = cloudWatchClient
        val client = injectedClient ?: createCloudWatchClient(defaults)
        val operations = CloudWatchKtorTemplate(client, namespace, batchSize)

        return CloudWatchKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createCloudWatchClient(defaults: AwsKtorDefaults): CloudWatchClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.endpointOverride?.let { Url.parse(it.toString()) }
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        return cloudWatchClientOf(
            endpointUrl = effectiveEndpoint,
            region = effectiveRegion,
            credentialsProvider = credentialsProvider ?: defaults.kotlinCredentialsProvider,
            httpClient = httpClient ?: defaults.kotlinHttpClient,
            builder = {
                defaults.cloudWatchClientCustomizers.forEach { it.customize(this) }
                clientCustomizers.forEach { it.customize(this) }
            },
        )
    }

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("enabled", enabled)
            .add("cloudWatchClientConfigured", cloudWatchClient != null)
            .add("clientCustomizersCount", clientCustomizers.size)
            .add("region", region)
            .add("endpointConfigured", endpointOverride != null)
            .add("credentialsProviderConfigured", credentialsProvider != null)
            .add("namespace", namespace)
            .add("batchSize", batchSize)
            .toString()
    }
}
