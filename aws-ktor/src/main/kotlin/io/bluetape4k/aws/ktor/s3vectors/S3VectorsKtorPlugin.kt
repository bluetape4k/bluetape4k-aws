package io.bluetape4k.aws.ktor.s3vectors

import io.bluetape4k.aws.ktor.awsKtorDefaults
import io.bluetape4k.aws.s3vectors.S3VectorsOperations
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 설치된 [S3VectorsKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val S3VectorsKtorRuntimeKey: AttributeKey<S3VectorsKtorRuntime> =
    AttributeKey("S3VectorsKtorRuntime")

/**
 * 설치된 [S3VectorsOperations]를 저장하는 애플리케이션 속성 키입니다.
 */
val S3VectorsOperationsKey: AttributeKey<S3VectorsOperations> =
    AttributeKey("S3VectorsOperations")

/**
 * 선택적인 Amazon S3 Vectors 작업용 Ktor 애플리케이션 플러그인입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치해도 AWS를 호출하지 않습니다. S3 Vectors 호출은
 * [S3VectorsOperations] 메서드를 통해서만 발생합니다.
 */
val S3VectorsKtorPlugin: ApplicationPlugin<S3VectorsKtorPluginConfig> = createApplicationPlugin(
    name = "S3VectorsKtorPlugin",
    createConfiguration = ::S3VectorsKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(S3VectorsKtorRuntimeKey, runtime)
        application.attributes.put(S3VectorsOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring events are synchronous; close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * [S3VectorsKtorPlugin]이 설치한 S3 Vectors 작업을 반환합니다.
 *
 * @throws IllegalStateException [S3VectorsKtorPlugin]이 없거나 비활성화된 경우
 */
fun Application.s3Vectors(): S3VectorsOperations =
    checkNotNull(s3VectorsOrNull()) {
        "S3VectorsKtorPlugin is not installed or is disabled."
    }

/**
 * [S3VectorsKtorPlugin]이 설치한 S3 Vectors 작업을 반환합니다. 플러그인이 없거나 비활성화되었으면 null을 반환합니다.
 */
fun Application.s3VectorsOrNull(): S3VectorsOperations? =
    attributes.getOrNull(S3VectorsOperationsKey)
