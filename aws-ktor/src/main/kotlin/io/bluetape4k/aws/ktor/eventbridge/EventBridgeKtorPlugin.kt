package io.bluetape4k.aws.ktor.eventbridge

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
 * 설치된 [EventBridgeKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val EventBridgeKtorRuntimeKey: AttributeKey<EventBridgeKtorRuntime> = AttributeKey("EventBridgeKtorRuntime")

/**
 * 설치된 [EventBridgeKtorOperations]를 저장하는 애플리케이션 속성 키입니다.
 */
val EventBridgeKtorOperationsKey: AttributeKey<EventBridgeKtorOperations> = AttributeKey("EventBridgeKtorOperations")

/**
 * EventBridge 작업용 Ktor 애플리케이션 플러그인입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치해도 이벤트를 전송하지 않습니다. EventBridge 호출은
 * [EventBridgeKtorOperations] 메서드를 통해서만 발생합니다.
 */
val EventBridgeKtorPlugin: ApplicationPlugin<EventBridgeKtorPluginConfig> = createApplicationPlugin(
    name = "EventBridgeKtorPlugin",
    createConfiguration = ::EventBridgeKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(EventBridgeKtorRuntimeKey, runtime)
        application.attributes.put(EventBridgeKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring event는 동기식이므로 SDK client는 IO에서 닫는다.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * [EventBridgeKtorPlugin]이 설치한 EventBridge 작업을 반환합니다.
 *
 * @throws IllegalStateException [EventBridgeKtorPlugin]이 없거나 비활성화된 경우
 */
fun Application.eventBridge(): EventBridgeKtorOperations =
    eventBridgeOrNull() ?: error("EventBridgeKtorPlugin is not installed or is disabled.")

/**
 * [EventBridgeKtorPlugin]이 설치한 EventBridge 작업을 반환합니다. 플러그인이 없거나 비활성화되었으면 null을 반환합니다.
 */
fun Application.eventBridgeOrNull(): EventBridgeKtorOperations? =
    attributes.getOrNull(EventBridgeKtorOperationsKey)
