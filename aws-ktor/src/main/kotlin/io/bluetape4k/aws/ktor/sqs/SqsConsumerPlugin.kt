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
 * Application attribute key that stores the installed [SqsConsumerRuntime].
 */
val SqsConsumerRuntimeKey: AttributeKey<SqsConsumerRuntime> = AttributeKey("SqsConsumerRuntime")

/**
 * Ktor application plugin for consuming and publishing SQS messages.
 *
 * Contract:
 * - Starts polling on [ApplicationStarted].
 * - Stops polling and drains in-flight handlers on [ApplicationStopping].
 * - Leaves the injected AWS SDK [software.amazon.awssdk.services.sqs.SqsAsyncClient] open.
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
        // Ktor monitoring events are synchronous; use IO while draining SQS handlers.
        runBlocking(Dispatchers.IO) {
            runtime.stop()
        }
    }
}

/**
 * Discoverability alias for the SQS Ktor integration.
 *
 * Prefer [SqsConsumer] in new code when the application only consumes one queue
 * per plugin instance.
 */
val SqsKtorPlugin: ApplicationPlugin<SqsConsumerPluginConfig> = SqsConsumer

/**
 * Returns the runtime installed by [SqsConsumer].
 */
fun Application.sqsConsumer(): SqsConsumerRuntime =
    attributes[SqsConsumerRuntimeKey]
