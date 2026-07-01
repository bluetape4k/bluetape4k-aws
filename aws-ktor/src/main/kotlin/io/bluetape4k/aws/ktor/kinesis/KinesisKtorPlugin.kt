package io.bluetape4k.aws.ktor.kinesis

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
 * Application attribute key that stores the installed [KinesisKtorRuntime].
 */
val KinesisKtorRuntimeKey: AttributeKey<KinesisKtorRuntime> = AttributeKey("KinesisKtorRuntime")

/**
 * Application attribute key that stores the installed [KinesisKtorOperations].
 */
val KinesisKtorOperationsKey: AttributeKey<KinesisKtorOperations> = AttributeKey("KinesisKtorOperations")

/**
 * Ktor application plugin for Kinesis operations.
 *
 * ## Contract
 *
 * Installing the plugin does not create streams, publish records, or start
 * consumers. Kinesis calls happen only through [KinesisKtorOperations].
 */
val KinesisKtorPlugin: ApplicationPlugin<KinesisKtorPluginConfig> = createApplicationPlugin(
    name = "KinesisKtorPlugin",
    createConfiguration = ::KinesisKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(KinesisKtorRuntimeKey, runtime)
        application.attributes.put(KinesisKtorOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * Returns Kinesis operations installed by [KinesisKtorPlugin].
 *
 * @throws IllegalStateException when [KinesisKtorPlugin] is absent or disabled.
 */
fun Application.kinesis(): KinesisKtorOperations =
    kinesisOrNull() ?: error("KinesisKtorPlugin is not installed or is disabled.")

/**
 * Returns Kinesis operations installed by [KinesisKtorPlugin], or null when absent or disabled.
 */
fun Application.kinesisOrNull(): KinesisKtorOperations? =
    attributes.getOrNull(KinesisKtorOperationsKey)
