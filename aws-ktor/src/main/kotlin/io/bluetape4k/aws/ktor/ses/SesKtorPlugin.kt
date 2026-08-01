package io.bluetape4k.aws.ktor.ses

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
 * 설치된 [SesKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val SesKtorRuntimeKey: AttributeKey<SesKtorRuntime> = AttributeKey("SesKtorRuntime")

/**
 * 설치된 [SesKtorOperations]를 저장하는 애플리케이션 속성 키입니다.
 */
val SesKtorOperationsKey: AttributeKey<SesKtorOperations> = AttributeKey("SesKtorOperations")

/**
 * SES v2 이메일 작업용 Ktor 애플리케이션 플러그인입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치해도 이메일을 전송하지 않습니다. 이메일 호출은
 * [SesKtorOperations] 메서드를 통해서만 발생합니다.
 */
val SesKtorPlugin: ApplicationPlugin<SesKtorPluginConfig> = createApplicationPlugin(
    name = "SesKtorPlugin",
    createConfiguration = ::SesKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(SesKtorRuntimeKey, runtime)
        application.attributes.put(SesKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring events are synchronous; close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * [SesKtorPlugin]이 설치한 SES 작업을 반환합니다.
 *
 * @throws IllegalStateException [SesKtorPlugin]이 없거나 비활성화된 경우
 */
fun Application.ses(): SesKtorOperations =
    sesOrNull() ?: error("SesKtorPlugin is not installed or is disabled.")

/**
 * [SesKtorPlugin]이 설치한 SES 작업을 반환합니다. 플러그인이 없거나 비활성화되었으면 null을 반환합니다.
 */
fun Application.sesOrNull(): SesKtorOperations? =
    attributes.getOrNull(SesKtorOperationsKey)
