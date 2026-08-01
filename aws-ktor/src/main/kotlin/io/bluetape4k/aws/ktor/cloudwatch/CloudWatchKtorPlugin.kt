package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.aws.ktor.awsKtorDefaults
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 설치된 [CloudWatchKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val CloudWatchKtorRuntimeKey: AttributeKey<CloudWatchKtorRuntime> = AttributeKey("CloudWatchKtorRuntime")

/**
 * 설치된 [CloudWatchKtorOperations]를 저장하는 애플리케이션 속성 키입니다.
 */
val CloudWatchKtorOperationsKey: AttributeKey<CloudWatchKtorOperations> = AttributeKey("CloudWatchKtorOperations")

/**
 * 명시적인 CloudWatch 메트릭 게시용 Ktor 애플리케이션 플러그인입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치해도 메트릭을 게시하지 않습니다. 메트릭 호출은
 * [CloudWatchKtorOperations] 메서드를 통해서만 발생합니다.
 */
val CloudWatchKtorPlugin: ApplicationPlugin<CloudWatchKtorPluginConfig> = createApplicationPlugin(
    name = "CloudWatchKtorPlugin",
    createConfiguration = ::CloudWatchKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(CloudWatchKtorRuntimeKey, runtime)
        application.attributes.put(CloudWatchKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring event는 동기식이므로 SDK client는 IO에서 닫는다.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * [CloudWatchKtorPlugin]이 설치한 CloudWatch 작업을 반환합니다.
 *
 * @throws IllegalStateException [CloudWatchKtorPlugin]이 없거나 비활성화된 경우
 */
fun Application.cloudWatch(): CloudWatchKtorOperations =
    cloudWatchOrNull() ?: error("CloudWatchKtorPlugin is not installed or is disabled.")

/**
 * [CloudWatchKtorPlugin]이 설치한 CloudWatch 작업을 반환합니다. 플러그인이 없거나 비활성화되었으면 null을 반환합니다.
 */
fun Application.cloudWatchOrNull(): CloudWatchKtorOperations? =
    attributes.getOrNull(CloudWatchKtorOperationsKey)
