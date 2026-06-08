package io.bluetape4k.aws.ktor.s3.accessgrants

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
 * Application attribute key that stores the installed [S3AccessGrantsKtorRuntime].
 */
val S3AccessGrantsKtorRuntimeKey: AttributeKey<S3AccessGrantsKtorRuntime> =
    AttributeKey("S3AccessGrantsKtorRuntime")

/**
 * Application attribute key that stores the installed [S3AccessGrantsKtorOperations].
 */
val S3AccessGrantsKtorOperationsKey: AttributeKey<S3AccessGrantsKtorOperations> =
    AttributeKey("S3AccessGrantsKtorOperations")

/**
 * Ktor application plugin for S3 Access Grants through AWS SDK v2 S3 Control.
 *
 * ## Contract
 *
 * Installing the plugin does not call AWS. Access Grants calls happen only
 * through [S3AccessGrantsKtorOperations] methods.
 */
val S3AccessGrantsKtorPlugin: ApplicationPlugin<S3AccessGrantsKtorPluginConfig> = createApplicationPlugin(
    name = "S3AccessGrantsKtorPlugin",
    createConfiguration = ::S3AccessGrantsKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(S3AccessGrantsKtorRuntimeKey, runtime)
        application.attributes.put(S3AccessGrantsKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring events are synchronous; close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * Returns S3 Access Grants operations installed by [S3AccessGrantsKtorPlugin].
 *
 * @throws IllegalStateException when [S3AccessGrantsKtorPlugin] is absent or disabled.
 */
fun Application.s3AccessGrants(): S3AccessGrantsKtorOperations =
    s3AccessGrantsOrNull() ?: throw IllegalStateException("S3AccessGrantsKtorPlugin is not installed or is disabled.")

/**
 * Returns S3 Access Grants operations installed by [S3AccessGrantsKtorPlugin], or null when absent or disabled.
 */
fun Application.s3AccessGrantsOrNull(): S3AccessGrantsKtorOperations? =
    attributes.getOrNull(S3AccessGrantsKtorOperationsKey)
