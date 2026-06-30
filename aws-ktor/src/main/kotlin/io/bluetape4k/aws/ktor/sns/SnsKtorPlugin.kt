package io.bluetape4k.aws.ktor.sns

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
 * Application attribute key that stores the installed [SnsKtorRuntime].
 */
val SnsKtorRuntimeKey: AttributeKey<SnsKtorRuntime> = AttributeKey("SnsKtorRuntime")

/**
 * Application attribute key that stores the installed [SnsKtorOperations].
 */
val SnsKtorOperationsKey: AttributeKey<SnsKtorOperations> = AttributeKey("SnsKtorOperations")

/**
 * Application attribute key that stores the installed [SnsHttpMessageParser].
 */
val SnsHttpMessageParserKey: AttributeKey<SnsHttpMessageParser> = AttributeKey("SnsHttpMessageParser")

/**
 * Ktor application plugin for SNS topic, publish, SMS, and HTTP message parsing operations.
 *
 * ## Contract
 *
 * Installing the plugin does not perform SNS calls. Calls happen only through
 * [SnsKtorOperations] methods, and parsed HTTP messages remain untrusted until
 * caller-owned signature verification wraps them as [TrustedSnsHttpMessage].
 */
val SnsKtorPlugin: ApplicationPlugin<SnsKtorPluginConfig> = createApplicationPlugin(
    name = "SnsKtorPlugin",
    createConfiguration = ::SnsKtorPluginConfig,
) {
    val runtime = pluginConfig.toRuntime(application.awsKtorDefaults())
    if (runtime != null) {
        application.attributes.put(SnsKtorRuntimeKey, runtime)
        application.attributes.put(SnsKtorOperationsKey, runtime.operations)
        application.attributes.put(SnsHttpMessageParserKey, runtime.parser)

        on(MonitoringEvent(ApplicationStopping)) {
            // Ktor monitoring events are synchronous; close SDK clients on IO.
            runBlocking(Dispatchers.IO) {
                runtime.stop()
            }
        }
    }
}

/**
 * Returns SNS operations installed by [SnsKtorPlugin].
 *
 * @throws IllegalStateException when [SnsKtorPlugin] is absent or disabled.
 */
fun Application.sns(): SnsKtorOperations =
    snsOrNull() ?: error("SnsKtorPlugin is not installed or is disabled.")

/**
 * Returns SNS operations installed by [SnsKtorPlugin], or null when absent or disabled.
 */
fun Application.snsOrNull(): SnsKtorOperations? =
    attributes.getOrNull(SnsKtorOperationsKey)

/**
 * Returns the SNS HTTP message parser installed by [SnsKtorPlugin].
 *
 * @throws IllegalStateException when [SnsKtorPlugin] is absent or disabled.
 */
fun Application.snsHttpMessageParser(): SnsHttpMessageParser =
    snsHttpMessageParserOrNull() ?: error("SnsKtorPlugin is not installed or is disabled.")

/**
 * Returns the SNS HTTP message parser installed by [SnsKtorPlugin], or null when absent or disabled.
 */
fun Application.snsHttpMessageParserOrNull(): SnsHttpMessageParser? =
    attributes.getOrNull(SnsHttpMessageParserKey)
