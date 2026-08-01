package io.bluetape4k.aws.ktor.kinesis

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
 * 설치된 [KinesisKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val KinesisKtorRuntimeKey: AttributeKey<KinesisKtorRuntime> = AttributeKey("KinesisKtorRuntime")

/**
 * 설치된 [KinesisKtorOperations]를 저장하는 애플리케이션 속성 키입니다.
 */
val KinesisKtorOperationsKey: AttributeKey<KinesisKtorOperations> = AttributeKey("KinesisKtorOperations")

/**
 * Kinesis 작업용 Ktor 애플리케이션 플러그인입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치해도 스트림을 생성하거나 레코드를 게시하거나 소비자를 시작하지 않습니다.
 * Kinesis 호출은 [KinesisKtorOperations]를 통해서만 발생합니다.
 */
val KinesisKtorPlugin: ApplicationPlugin<KinesisKtorPluginConfig> = createApplicationPlugin(
    name = "KinesisKtorPlugin",
    createConfiguration = ::KinesisKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(KinesisKtorRuntimeKey, runtime)
        application.attributes.put(KinesisKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * [KinesisKtorPlugin]이 설치한 Kinesis 작업을 반환합니다.
 *
 * @throws IllegalStateException [KinesisKtorPlugin]이 없거나 비활성화된 경우
 */
fun Application.kinesis(): KinesisKtorOperations =
    kinesisOrNull() ?: error("KinesisKtorPlugin is not installed or is disabled.")

/**
 * [KinesisKtorPlugin]이 설치한 Kinesis 작업을 반환합니다. 플러그인이 없거나 비활성화되었으면 null을 반환합니다.
 */
fun Application.kinesisOrNull(): KinesisKtorOperations? =
    attributes.getOrNull(KinesisKtorOperationsKey)
