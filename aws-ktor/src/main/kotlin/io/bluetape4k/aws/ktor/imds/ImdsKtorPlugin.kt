package io.bluetape4k.aws.ktor.imds

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 설치된 [ImdsKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val ImdsKtorRuntimeKey: AttributeKey<ImdsKtorRuntime> = AttributeKey("ImdsKtorRuntime")

/**
 * 설치된 [ImdsKtorOperations]를 저장하는 애플리케이션 속성 키입니다.
 */
val ImdsKtorOperationsKey: AttributeKey<ImdsKtorOperations> = AttributeKey("ImdsKtorOperations")

/**
 * EC2 Instance Metadata Service 접근용 Ktor 애플리케이션 플러그인입니다.
 *
 * ## 계약
 *
 * 플러그인은 설치 중 IMDS를 호출하지 않고 [ImdsKtorOperations] 파사드를 생성하거나 저장합니다.
 * 메타데이터 호출은 애플리케이션 코드가 작업을 호출할 때만 수행됩니다.
 */
val ImdsKtorPlugin: ApplicationPlugin<ImdsKtorPluginConfig> = createApplicationPlugin(
    name = "ImdsKtorPlugin",
    createConfiguration = ::ImdsKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime()
    if (runtime != null) {
        application.attributes.put(ImdsKtorRuntimeKey, runtime)
        application.attributes.put(ImdsKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring event는 동기식이므로 SDK client는 IO에서 닫는다.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * [ImdsKtorPlugin]이 설치한 IMDS 작업을 반환합니다.
 *
 * @throws IllegalStateException [ImdsKtorPlugin]이 없거나 비활성화된 경우
 */
fun Application.imds(): ImdsKtorOperations =
    imdsOrNull() ?: throw IllegalStateException("ImdsKtorPlugin is not installed or is disabled.")

/**
 * [ImdsKtorPlugin]이 설치한 IMDS 작업을 반환합니다. 플러그인이 없거나 비활성화되었으면 null을 반환합니다.
 */
fun Application.imdsOrNull(): ImdsKtorOperations? =
    attributes.getOrNull(ImdsKtorOperationsKey)
