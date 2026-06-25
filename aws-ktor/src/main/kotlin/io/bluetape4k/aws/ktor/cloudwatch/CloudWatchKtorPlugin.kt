package io.bluetape4k.aws.ktor.cloudwatch

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
 * Application attribute key that stores the installed [CloudWatchKtorRuntime].
 */
val CloudWatchKtorRuntimeKey: AttributeKey<CloudWatchKtorRuntime> = AttributeKey("CloudWatchKtorRuntime")

/**
 * Application attribute key that stores the installed [CloudWatchKtorOperations].
 */
val CloudWatchKtorOperationsKey: AttributeKey<CloudWatchKtorOperations> = AttributeKey("CloudWatchKtorOperations")

/**
 * Ktor application plugin for explicit CloudWatch metric publishing.
 *
 * ## Contract
 *
 * Installing the plugin does not publish metrics. Metric calls happen only
 * through [CloudWatchKtorOperations] methods.
 */
val CloudWatchKtorPlugin: ApplicationPlugin<CloudWatchKtorPluginConfig> = createApplicationPlugin(
    name = "CloudWatchKtorPlugin",
    createConfiguration = ::CloudWatchKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(CloudWatchKtorRuntimeKey, runtime)
        application.attributes.put(CloudWatchKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring events are synchronous; close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * Returns CloudWatch operations installed by [CloudWatchKtorPlugin].
 *
 * @throws IllegalStateException when [CloudWatchKtorPlugin] is absent or disabled.
 */
fun Application.cloudWatch(): CloudWatchKtorOperations =
    cloudWatchOrNull() ?: error("CloudWatchKtorPlugin is not installed or is disabled.")

/**
 * Returns CloudWatch operations installed by [CloudWatchKtorPlugin], or null when absent or disabled.
 */
fun Application.cloudWatchOrNull(): CloudWatchKtorOperations? =
    attributes.getOrNull(CloudWatchKtorOperationsKey)
