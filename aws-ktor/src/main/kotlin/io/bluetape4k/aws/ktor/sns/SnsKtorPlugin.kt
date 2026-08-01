package io.bluetape4k.aws.ktor.sns

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
 * 설치된 [SnsKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val SnsKtorRuntimeKey: AttributeKey<SnsKtorRuntime> = AttributeKey("SnsKtorRuntime")

/**
 * 설치된 [SnsKtorOperations]를 저장하는 애플리케이션 속성 키입니다.
 */
val SnsKtorOperationsKey: AttributeKey<SnsKtorOperations> = AttributeKey("SnsKtorOperations")

/**
 * 설치된 [SnsHttpMessageParser]를 저장하는 애플리케이션 속성 키입니다.
 */
val SnsHttpMessageParserKey: AttributeKey<SnsHttpMessageParser> = AttributeKey("SnsHttpMessageParser")

/**
 * SNS 주제, 게시, SMS, HTTP 메시지 파싱 작업용 Ktor 애플리케이션 플러그인입니다.
 *
 * ## 계약
 *
 * 플러그인을 설치해도 SNS를 호출하지 않습니다. 호출은 [SnsKtorOperations] 메서드를 통해서만
 * 발생하며, 파싱된 HTTP 메시지는 호출자가 서명을 검증해 [TrustedSnsHttpMessage]로 감쌀 때까지
 * 신뢰되지 않은 상태입니다.
 */
val SnsKtorPlugin: ApplicationPlugin<SnsKtorPluginConfig> = createApplicationPlugin(
    name = "SnsKtorPlugin",
    createConfiguration = ::SnsKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(SnsKtorRuntimeKey, runtime)
        application.attributes.put(SnsKtorOperationsKey, runtime.operations)
        application.attributes.put(SnsHttpMessageParserKey, runtime.parser)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring event는 동기식이므로 SDK client는 IO에서 닫는다.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * [SnsKtorPlugin]이 설치한 SNS 작업을 반환합니다.
 *
 * @throws IllegalStateException [SnsKtorPlugin]이 없거나 비활성화된 경우
 */
fun Application.sns(): SnsKtorOperations =
    snsOrNull() ?: error("SnsKtorPlugin is not installed or is disabled.")

/**
 * [SnsKtorPlugin]이 설치한 SNS 작업을 반환합니다. 플러그인이 없거나 비활성화되었으면 null을 반환합니다.
 */
fun Application.snsOrNull(): SnsKtorOperations? =
    attributes.getOrNull(SnsKtorOperationsKey)

/**
 * [SnsKtorPlugin]이 설치한 SNS HTTP 메시지 파서를 반환합니다.
 *
 * @throws IllegalStateException [SnsKtorPlugin]이 없거나 비활성화된 경우
 */
fun Application.snsHttpMessageParser(): SnsHttpMessageParser =
    snsHttpMessageParserOrNull() ?: error("SnsKtorPlugin is not installed or is disabled.")

/**
 * [SnsKtorPlugin]이 설치한 SNS HTTP 메시지 파서를 반환합니다. 플러그인이 없거나 비활성화되었으면 null을 반환합니다.
 */
fun Application.snsHttpMessageParserOrNull(): SnsHttpMessageParser? =
    attributes.getOrNull(SnsHttpMessageParserKey)
