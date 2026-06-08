package io.bluetape4k.aws.ktor.s3vectors

import io.bluetape4k.aws.ktor.awsKtorDefaults
import io.bluetape4k.aws.s3vectors.S3VectorsOperations
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Application attribute key that stores the installed [S3VectorsKtorRuntime].
 */
val S3VectorsKtorRuntimeKey: AttributeKey<S3VectorsKtorRuntime> =
    AttributeKey("S3VectorsKtorRuntime")

/**
 * Application attribute key that stores the installed [S3VectorsOperations].
 */
val S3VectorsOperationsKey: AttributeKey<S3VectorsOperations> =
    AttributeKey("S3VectorsOperations")

/**
 * Ktor application plugin for optional Amazon S3 Vectors operations.
 *
 * ## Contract
 *
 * Installing the plugin does not call AWS. S3 Vectors calls happen only through
 * [S3VectorsOperations] methods.
 */
val S3VectorsKtorPlugin: ApplicationPlugin<S3VectorsKtorPluginConfig> = createApplicationPlugin(
    name = "S3VectorsKtorPlugin",
    createConfiguration = ::S3VectorsKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(S3VectorsKtorRuntimeKey, runtime)
        application.attributes.put(S3VectorsOperationsKey, runtime.operations)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring events are synchronous; close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * Returns S3 Vectors operations installed by [S3VectorsKtorPlugin].
 *
 * @throws IllegalStateException when [S3VectorsKtorPlugin] is absent or disabled.
 */
fun Application.s3Vectors(): S3VectorsOperations =
    checkNotNull(s3VectorsOrNull()) {
        "S3VectorsKtorPlugin is not installed or is disabled."
    }

/**
 * Returns S3 Vectors operations installed by [S3VectorsKtorPlugin], or null when absent or disabled.
 */
fun Application.s3VectorsOrNull(): S3VectorsOperations? =
    attributes.getOrNull(S3VectorsOperationsKey)
