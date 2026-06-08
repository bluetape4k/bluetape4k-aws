package io.bluetape4k.aws.ktor.cloudwatch

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
 * Application attribute key that stores the installed [CloudWatchLogsKtorRuntime].
 */
val CloudWatchLogsKtorRuntimeKey: AttributeKey<CloudWatchLogsKtorRuntime> = AttributeKey("CloudWatchLogsKtorRuntime")

/**
 * Application attribute key that stores the installed [CloudWatchLogsKtorOperations].
 */
val CloudWatchLogsKtorOperationsKey: AttributeKey<CloudWatchLogsKtorOperations> =
    AttributeKey("CloudWatchLogsKtorOperations")

/**
 * Ktor application plugin for explicit CloudWatch Logs operations and buffered publishing.
 *
 * ## Contract
 *
 * Installing the plugin does not publish log events. Buffered publishing starts
 * only after application code appends events to [CloudWatchLogsKtorRuntime].
 */
val CloudWatchLogsKtorPlugin: ApplicationPlugin<CloudWatchLogsKtorPluginConfig> = createApplicationPlugin(
    name = "CloudWatchLogsKtorPlugin",
    createConfiguration = ::CloudWatchLogsKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(CloudWatchLogsKtorRuntimeKey, runtime)
        application.attributes.put(CloudWatchLogsKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStarted)) {
            // Ktor monitoring events are synchronous; startup setup can call AWS.
            runBlocking(Dispatchers.IO) {
                runtime.start()
            }
        }
        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring events are synchronous; flush and close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * Returns CloudWatch Logs operations installed by [CloudWatchLogsKtorPlugin].
 *
 * @throws IllegalStateException when [CloudWatchLogsKtorPlugin] is absent or disabled.
 */
fun Application.cloudWatchLogs(): CloudWatchLogsKtorOperations =
    cloudWatchLogsOrNull() ?: throw IllegalStateException("CloudWatchLogsKtorPlugin is not installed or is disabled.")

/**
 * Returns CloudWatch Logs operations installed by [CloudWatchLogsKtorPlugin], or null when absent or disabled.
 */
fun Application.cloudWatchLogsOrNull(): CloudWatchLogsKtorOperations? =
    attributes.getOrNull(CloudWatchLogsKtorOperationsKey)
