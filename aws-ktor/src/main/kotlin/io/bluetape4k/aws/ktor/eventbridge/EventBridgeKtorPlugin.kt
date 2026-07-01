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
 * Application attribute key that stores the installed [EventBridgeKtorRuntime].
 */
val EventBridgeKtorRuntimeKey: AttributeKey<EventBridgeKtorRuntime> = AttributeKey("EventBridgeKtorRuntime")

/**
 * Application attribute key that stores the installed [EventBridgeKtorOperations].
 */
val EventBridgeKtorOperationsKey: AttributeKey<EventBridgeKtorOperations> = AttributeKey("EventBridgeKtorOperations")

/**
 * Ktor application plugin for EventBridge operations.
 *
 * ## Contract
 *
 * Installing the plugin does not send events. EventBridge calls happen only
 * through [EventBridgeKtorOperations] methods.
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
            // Ktor monitoring events are synchronous; close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * Returns EventBridge operations installed by [EventBridgeKtorPlugin].
 *
 * @throws IllegalStateException when [EventBridgeKtorPlugin] is absent or disabled.
 */
fun Application.eventBridge(): EventBridgeKtorOperations =
    eventBridgeOrNull() ?: error("EventBridgeKtorPlugin is not installed or is disabled.")

/**
 * Returns EventBridge operations installed by [EventBridgeKtorPlugin], or null when absent or disabled.
 */
fun Application.eventBridgeOrNull(): EventBridgeKtorOperations? =
    attributes.getOrNull(EventBridgeKtorOperationsKey)
