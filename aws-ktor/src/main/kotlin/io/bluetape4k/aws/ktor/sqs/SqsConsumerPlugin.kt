package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.aws.ktor.awsKtorDefaults
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 설치된 [SqsConsumerRuntime]을 저장하는 Ktor application attribute key입니다.
 */
val SqsConsumerRuntimeKey: AttributeKey<SqsConsumerRuntime> = AttributeKey("SqsConsumerRuntime")

/**
 * SQS 메시지를 소비하고 발행하는 Ktor application plugin입니다.
 *
 * 계약:
 * - [ApplicationStarted] 이벤트에서 polling을 시작합니다.
 * - [ApplicationStopping] 이벤트에서 polling을 멈추고 처리 중인 handler를 drain합니다.
 * - 주입된 AWS SDK [software.amazon.awssdk.services.sqs.SqsAsyncClient]는 닫지 않습니다.
 */
val SqsConsumer: ApplicationPlugin<SqsConsumerPluginConfig> = createApplicationPlugin(
    name = "SqsConsumer",
    createConfiguration = ::SqsConsumerPluginConfig,
) {
    val runtime = SqsConsumerRuntime(pluginConfig.toRuntimeConfig(application.awsKtorDefaults()))
    application.attributes.put(SqsConsumerRuntimeKey, runtime)

    on(MonitoringEvent(ApplicationStarted)) {
        runtime.start()
    }
    on(MonitoringEvent(ApplicationStopping)) {
        // Ktor monitoring event는 동기식이므로 SQS handler를 drain하는 동안 IO dispatcher를 사용합니다.
        runBlocking(Dispatchers.IO) {
            runtime.stop()
        }
    }
}

/**
 * SQS Ktor integration을 쉽게 찾을 수 있게 하는 alias입니다.
 *
 * plugin instance 하나가 queue 하나만 소비하는 새 코드에서는 [SqsConsumer]를 우선 사용합니다.
 */
val SqsKtorPlugin: ApplicationPlugin<SqsConsumerPluginConfig> = SqsConsumer

/**
 * [SqsConsumer]가 설치한 runtime을 반환합니다.
 */
fun Application.sqsConsumer(): SqsConsumerRuntime =
    attributes[SqsConsumerRuntimeKey]
