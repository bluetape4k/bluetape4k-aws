package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.aws.ktor.AwsKtorCloudWatchLogsAsyncClientCustomizer
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import java.net.URI
import java.time.Duration

/**
 * [CloudWatchLogsKtorPlugin] 구성입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치하면 작업과 런타임만 등록합니다. 명시적으로 구성하거나 호출하지 않으면
 * 로그 그룹이나 스트림을 생성하거나 이벤트를 게시하지 않습니다.
 */
class CloudWatchLogsKtorPluginConfig {

    /** Ktor CloudWatch Logs 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 AWS SDK v2 CloudWatch Logs 비동기 클라이언트입니다. */
    var cloudWatchLogsAsyncClient: CloudWatchLogsAsyncClient? = null

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var cloudWatchLogsOperations: CloudWatchLogsKtorOperations? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 CloudWatch Logs 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 CloudWatch Logs 엔드포인트 재정의입니다. */
    var endpointOverride: URI? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** 스트림 식별자를 생략한 작업에 사용할 기본 CloudWatch Logs 그룹 이름입니다. */
    var logGroupName: String? = null

    /** 스트림 식별자를 생략한 작업에 사용할 기본 CloudWatch Logs 스트림 이름입니다. */
    var logStreamName: String? = null

    /** CloudWatch Logs PutLogEvents 배치 크기입니다. AWS는 1..10000을 허용합니다. */
    var batchSize: Int = CLOUDWATCH_LOGS_MAX_BATCH_SIZE

    /** 명시적으로 버퍼링한 로그 이벤트의 주기적 flush 간격입니다. */
    var flushInterval: Duration = Duration.ofSeconds(5)

    /** 종료 시 flush에 허용하는 최대 시간입니다. */
    var shutdownFlushTimeout: Duration = Duration.ofSeconds(5)

    /** 종료 flush timeout을 warning으로 처리할지 예외로 전파할지 선택합니다. */
    var shutdownPolicy: CloudWatchLogsShutdownPolicy = CloudWatchLogsShutdownPolicy.WarnAndContinue

    /** 애플리케이션 시작 시 구성된 로그 그룹을 생성합니다. */
    var createLogGroupOnStart: Boolean = false

    /** 애플리케이션 시작 시 구성된 로그 스트림을 생성합니다. */
    var createLogStreamOnStart: Boolean = false

    private val clientCustomizers = mutableListOf<AwsKtorCloudWatchLogsAsyncClientCustomizer>()
    private val shutdownObservers = mutableListOf<CloudWatchLogsShutdownObserver>()

    /**
     * 플러그인이 생성한 클라이언트에 CloudWatch Logs 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun cloudWatchLogsAsyncClient(customizer: AwsKtorCloudWatchLogsAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    /** 종료 flush의 pending/dropped event를 관찰할 observer를 추가합니다. */
    fun shutdownObserver(observer: CloudWatchLogsShutdownObserver) {
        shutdownObservers += observer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): CloudWatchLogsKtorRuntime? {
        if (!enabled) {
            return null
        }

        val defaultLogStream = defaultLogStreamOrNull()
        cloudWatchLogsOperations?.let {
            return CloudWatchLogsKtorRuntime(
                operations = it,
                logStream = defaultLogStream,
                batchSize = batchSize,
                flushInterval = flushInterval,
                shutdownFlushTimeout = shutdownFlushTimeout,
                shutdownPolicy = shutdownPolicy,
                shutdownObservers = shutdownObservers.toList(),
                createLogGroupOnStart = createLogGroupOnStart,
                createLogStreamOnStart = createLogStreamOnStart,
            )
        }

        val injectedClient = cloudWatchLogsAsyncClient
        val client = injectedClient ?: createCloudWatchLogsAsyncClient(defaults)
        val operations = CloudWatchLogsKtorTemplate(client, defaultLogStream, batchSize)

        return CloudWatchLogsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
            logStream = defaultLogStream,
            batchSize = batchSize,
            flushInterval = flushInterval,
            shutdownFlushTimeout = shutdownFlushTimeout,
            shutdownPolicy = shutdownPolicy,
            shutdownObservers = shutdownObservers.toList(),
            createLogGroupOnStart = createLogGroupOnStart,
            createLogStreamOnStart = createLogStreamOnStart,
        )
    }

    private fun defaultLogStreamOrNull(): CloudWatchLogStream? {
        val groupName = logGroupName?.takeIf { it.isNotBlank() }
        val streamName = logStreamName?.takeIf { it.isNotBlank() }
        return when {
            groupName == null && streamName == null -> null
            groupName != null && streamName != null -> CloudWatchLogStream(groupName, streamName)
            else -> throw IllegalArgumentException("logGroupName and logStreamName must be configured together.")
        }
    }

    private fun createCloudWatchLogsAsyncClient(defaults: AwsKtorDefaults): CloudWatchLogsAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = CloudWatchLogsAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.cloudWatchLogsAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
