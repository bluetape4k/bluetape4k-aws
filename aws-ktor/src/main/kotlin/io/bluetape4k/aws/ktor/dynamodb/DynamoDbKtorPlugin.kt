package io.bluetape4k.aws.ktor.dynamodb

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
 * 설치된 [DynamoDbKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val DynamoDbKtorRuntimeKey: AttributeKey<DynamoDbKtorRuntime> = AttributeKey("DynamoDbKtorRuntime")

/**
 * AWS Kotlin SDK DynamoDB 통합용 Ktor 애플리케이션 플러그인입니다.
 *
 * 계약:
 * - `autoCreateTables = true`이면 [ApplicationStarted]에서 명시적으로 등록한 누락 테이블을 생성합니다.
 * - [ApplicationStopping]에서 플러그인이 생성한 클라이언트만 닫습니다.
 * - 리포지토리가 접근할 수 있도록 [DynamoDbKtorRuntime]을 애플리케이션 속성에 저장합니다.
 */
val DynamoDbKtorPlugin: ApplicationPlugin<DynamoDbKtorPluginConfig> = createApplicationPlugin(
    name = "DynamoDbKtorPlugin",
    createConfiguration = ::DynamoDbKtorPluginConfig,
) {
    val runtime = DynamoDbKtorRuntime(pluginConfig.toRuntimeConfig(application.awsKtorDefaults()))
    application.attributes.put(DynamoDbKtorRuntimeKey, runtime)

    on(MonitoringEvent(ApplicationStarted)) {
        // Ktor monitoring events are synchronous; table auto-creation is suspend-only AWS Kotlin SDK work.
        runBlocking(Dispatchers.IO) {
            runtime.start()
        }
    }
    on(MonitoringEvent(ApplicationStopping)) {
        // Ktor monitoring events are synchronous; close plugin-owned AWS clients within a bounded suspend bridge.
        runBlocking(Dispatchers.IO) {
            runtime.stop()
        }
    }
}

/**
 * [DynamoDbKtorPlugin]이 설치한 런타임을 반환합니다.
 */
fun Application.dynamoDb(): DynamoDbKtorRuntime =
    attributes[DynamoDbKtorRuntimeKey]
