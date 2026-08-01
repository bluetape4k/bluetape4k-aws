package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.aws.ktor.awsKtorDefaults
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 설치된 [CloudWatchLogsKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val CloudWatchLogsKtorRuntimeKey: AttributeKey<CloudWatchLogsKtorRuntime> = AttributeKey("CloudWatchLogsKtorRuntime")

/**
 * 설치된 [CloudWatchLogsKtorOperations]를 저장하는 애플리케이션 속성 키입니다.
 */
val CloudWatchLogsKtorOperationsKey: AttributeKey<CloudWatchLogsKtorOperations> =
    AttributeKey("CloudWatchLogsKtorOperations")

/**
 * 명시적인 CloudWatch Logs 작업과 버퍼링 게시를 위한 Ktor 애플리케이션 플러그인입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치해도 로그 이벤트를 게시하지 않습니다. 애플리케이션 코드가
 * [CloudWatchLogsKtorRuntime]에 이벤트를 추가한 뒤에만 버퍼링 게시가 시작됩니다.
 */
val CloudWatchLogsKtorPlugin: ApplicationPlugin<CloudWatchLogsKtorPluginConfig> = createApplicationPlugin(
    name = "CloudWatchLogsKtorPlugin",
    createConfiguration = ::CloudWatchLogsKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(CloudWatchLogsKtorRuntimeKey, runtime)
        application.attributes.put(CloudWatchLogsKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStarted)) {
            // Ktor monitoring events are synchronous; startup setup can call AWS.
            runBlocking(Dispatchers.IO) {
                runtime.start()
            }
        }
        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring events are synchronous; flush and close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * [CloudWatchLogsKtorPlugin]이 설치한 CloudWatch Logs 작업을 반환합니다.
 *
 * @throws IllegalStateException [CloudWatchLogsKtorPlugin]이 없거나 비활성화된 경우
 */
fun Application.cloudWatchLogs(): CloudWatchLogsKtorOperations =
    cloudWatchLogsOrNull() ?: error("CloudWatchLogsKtorPlugin is not installed or is disabled.")

/**
 * [CloudWatchLogsKtorPlugin]이 설치한 CloudWatch Logs 작업을 반환합니다. 플러그인이 없거나 비활성화되었으면 null을 반환합니다.
 */
fun Application.cloudWatchLogsOrNull(): CloudWatchLogsKtorOperations? =
    attributes.getOrNull(CloudWatchLogsKtorOperationsKey)
