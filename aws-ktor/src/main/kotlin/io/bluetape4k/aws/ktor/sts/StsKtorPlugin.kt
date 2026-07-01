package io.bluetape4k.aws.ktor.sts

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
 * Application attribute key that stores the installed [StsKtorRuntime].
 */
val StsKtorRuntimeKey: AttributeKey<StsKtorRuntime> = AttributeKey("StsKtorRuntime")

/**
 * Application attribute key that stores the installed [StsKtorOperations].
 */
val StsKtorOperationsKey: AttributeKey<StsKtorOperations> = AttributeKey("StsKtorOperations")

/**
 * Ktor application plugin for STS operations.
 *
 * ## Contract
 *
 * Installing the plugin does not request identity or credentials. STS calls
 * happen only through [StsKtorOperations].
 */
val StsKtorPlugin: ApplicationPlugin<StsKtorPluginConfig> = createApplicationPlugin(
    name = "StsKtorPlugin",
    createConfiguration = ::StsKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(StsKtorRuntimeKey, runtime)
        application.attributes.put(StsKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * Returns STS operations installed by [StsKtorPlugin].
 *
 * @throws IllegalStateException when [StsKtorPlugin] is absent or disabled.
 */
fun Application.sts(): StsKtorOperations =
    stsOrNull() ?: error("StsKtorPlugin is not installed or is disabled.")

/**
 * Returns STS operations installed by [StsKtorPlugin], or null when absent or disabled.
 */
fun Application.stsOrNull(): StsKtorOperations? =
    attributes.getOrNull(StsKtorOperationsKey)
