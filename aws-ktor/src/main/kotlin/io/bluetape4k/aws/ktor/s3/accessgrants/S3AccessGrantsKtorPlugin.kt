package io.bluetape4k.aws.ktor.s3.accessgrants

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
 * 설치된 [S3AccessGrantsKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val S3AccessGrantsKtorRuntimeKey: AttributeKey<S3AccessGrantsKtorRuntime> =
    AttributeKey("S3AccessGrantsKtorRuntime")

/**
 * 설치된 [S3AccessGrantsKtorOperations]를 저장하는 애플리케이션 속성 키입니다.
 */
val S3AccessGrantsKtorOperationsKey: AttributeKey<S3AccessGrantsKtorOperations> =
    AttributeKey("S3AccessGrantsKtorOperations")

/**
 * AWS SDK v2 S3 Control을 통한 S3 Access Grants용 Ktor 애플리케이션 플러그인입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치해도 AWS를 호출하지 않습니다. Access Grants 호출은
 * [S3AccessGrantsKtorOperations] 메서드를 통해서만 발생합니다.
 */
val S3AccessGrantsKtorPlugin: ApplicationPlugin<S3AccessGrantsKtorPluginConfig> = createApplicationPlugin(
    name = "S3AccessGrantsKtorPlugin",
    createConfiguration = ::S3AccessGrantsKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(S3AccessGrantsKtorRuntimeKey, runtime)
        application.attributes.put(S3AccessGrantsKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring events are synchronous; close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * [S3AccessGrantsKtorPlugin]이 설치한 S3 Access Grants 작업을 반환합니다.
 *
 * @throws IllegalStateException [S3AccessGrantsKtorPlugin]이 없거나 비활성화된 경우
 */
fun Application.s3AccessGrants(): S3AccessGrantsKtorOperations =
    s3AccessGrantsOrNull() ?: throw IllegalStateException("S3AccessGrantsKtorPlugin is not installed or is disabled.")

/**
 * [S3AccessGrantsKtorPlugin]이 설치한 S3 Access Grants 작업을 반환합니다. 플러그인이 없거나 비활성화되었으면 null을 반환합니다.
 */
fun Application.s3AccessGrantsOrNull(): S3AccessGrantsKtorOperations? =
    attributes.getOrNull(S3AccessGrantsKtorOperationsKey)
