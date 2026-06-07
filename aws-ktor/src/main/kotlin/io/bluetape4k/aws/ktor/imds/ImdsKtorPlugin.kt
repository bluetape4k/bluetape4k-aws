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
 * Application attribute key that stores the installed [ImdsKtorRuntime].
 */
val ImdsKtorRuntimeKey: AttributeKey<ImdsKtorRuntime> = AttributeKey("ImdsKtorRuntime")

/**
 * Application attribute key that stores the installed [ImdsKtorOperations].
 */
val ImdsKtorOperationsKey: AttributeKey<ImdsKtorOperations> = AttributeKey("ImdsKtorOperations")

/**
 * Ktor application plugin for EC2 Instance Metadata Service access.
 *
 * ## Contract
 *
 * The plugin creates or stores an [ImdsKtorOperations] facade during
 * installation without calling IMDS. Metadata calls are performed only when
 * application code invokes the operations.
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
            // Ktor monitoring events are synchronous; close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * Returns IMDS operations installed by [ImdsKtorPlugin].
 *
 * @throws IllegalStateException when [ImdsKtorPlugin] is absent or disabled.
 */
fun Application.imds(): ImdsKtorOperations =
    imdsOrNull() ?: throw IllegalStateException("ImdsKtorPlugin is not installed or is disabled.")

/**
 * Returns IMDS operations installed by [ImdsKtorPlugin], or null when absent or disabled.
 */
fun Application.imdsOrNull(): ImdsKtorOperations? =
    attributes.getOrNull(ImdsKtorOperationsKey)

