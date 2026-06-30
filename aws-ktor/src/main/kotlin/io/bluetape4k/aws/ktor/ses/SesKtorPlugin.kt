package io.bluetape4k.aws.ktor.ses

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
 * Application attribute key that stores the installed [SesKtorRuntime].
 */
val SesKtorRuntimeKey: AttributeKey<SesKtorRuntime> = AttributeKey("SesKtorRuntime")

/**
 * Application attribute key that stores the installed [SesKtorOperations].
 */
val SesKtorOperationsKey: AttributeKey<SesKtorOperations> = AttributeKey("SesKtorOperations")

/**
 * Ktor application plugin for SES v2 email operations.
 *
 * ## Contract
 *
 * Installing the plugin does not send email. Email calls happen only through
 * [SesKtorOperations] methods.
 */
val SesKtorPlugin: ApplicationPlugin<SesKtorPluginConfig> = createApplicationPlugin(
    name = "SesKtorPlugin",
    createConfiguration = ::SesKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(SesKtorRuntimeKey, runtime)
        application.attributes.put(SesKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring events are synchronous; close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * Returns SES operations installed by [SesKtorPlugin].
 *
 * @throws IllegalStateException when [SesKtorPlugin] is absent or disabled.
 */
fun Application.ses(): SesKtorOperations =
    sesOrNull() ?: error("SesKtorPlugin is not installed or is disabled.")

/**
 * Returns SES operations installed by [SesKtorPlugin], or null when absent or disabled.
 */
fun Application.sesOrNull(): SesKtorOperations? =
    attributes.getOrNull(SesKtorOperationsKey)
