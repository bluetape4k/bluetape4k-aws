package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.aws.ktor.AwsKtorCloudWatchAsyncClientCustomizer
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import java.net.URI

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

    /** 애플리케이션이 소유하는 선택적인 AWS SDK v2 CloudWatch 비동기 클라이언트입니다. */
    var cloudWatchAsyncClient: CloudWatchAsyncClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var cloudWatchOperations: CloudWatchKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 CloudWatch 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 CloudWatch 엔드포인트 재정의입니다. */
    var endpointOverride: URI? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** 네임스페이스를 생략한 작업에 사용할 기본 CloudWatch 네임스페이스입니다. */
    var namespace: String? = null

    /** CloudWatch PutMetricData 배치 크기입니다. AWS는 1..1000을 허용합니다. */
    var batchSize: Int = CLOUDWATCH_MAX_BATCH_SIZE

    private val clientCustomizers = mutableListOf<AwsKtorCloudWatchAsyncClientCustomizer>()

    /**
     * 플러그인이 생성한 클라이언트에 CloudWatch 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun cloudWatchAsyncClient(customizer: AwsKtorCloudWatchAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): CloudWatchKtorRuntime? {
        if (!enabled) {
            return null
        }

        cloudWatchOperations?.let { return CloudWatchKtorRuntime(it) }

        val injectedClient = cloudWatchAsyncClient
        val client = injectedClient ?: createCloudWatchAsyncClient(defaults)
        val operations = CloudWatchKtorTemplate(client, namespace, batchSize)

        return CloudWatchKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createCloudWatchAsyncClient(defaults: AwsKtorDefaults): CloudWatchAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = CloudWatchAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.cloudWatchAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
