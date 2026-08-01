package io.bluetape4k.aws.ktor.sts

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
 * 설치된 [StsKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val StsKtorRuntimeKey: AttributeKey<StsKtorRuntime> = AttributeKey("StsKtorRuntime")

/**
 * 설치된 [StsKtorOperations]를 저장하는 애플리케이션 속성 키입니다.
 */
val StsKtorOperationsKey: AttributeKey<StsKtorOperations> = AttributeKey("StsKtorOperations")

/**
 * STS 작업용 Ktor 애플리케이션 플러그인입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치해도 자격 또는 자격 증명을 요청하지 않습니다. STS 호출은 [StsKtorOperations]를 통해서만 발생합니다.
 */
val StsKtorPlugin: ApplicationPlugin<StsKtorPluginConfig> = createApplicationPlugin(
    name = "StsKtorPlugin",
    createConfiguration = ::StsKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(StsKtorRuntimeKey, runtime)
        application.attributes.put(StsKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * [StsKtorPlugin]이 설치한 STS 작업을 반환합니다.
 *
 * @throws IllegalStateException [StsKtorPlugin]이 없거나 비활성화된 경우
 */
fun Application.sts(): StsKtorOperations =
    stsOrNull() ?: error("StsKtorPlugin is not installed or is disabled.")

/**
 * [StsKtorPlugin]이 설치한 STS 작업을 반환합니다. 플러그인이 없거나 비활성화되었으면 null을 반환합니다.
 */
fun Application.stsOrNull(): StsKtorOperations? =
    attributes.getOrNull(StsKtorOperationsKey)
