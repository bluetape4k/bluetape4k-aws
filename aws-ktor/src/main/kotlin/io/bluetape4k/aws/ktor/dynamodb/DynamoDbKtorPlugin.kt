package io.bluetape4k.aws.ktor.dynamodb

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
 * Application attribute key that stores the installed [DynamoDbKtorRuntime].
 */
val DynamoDbKtorRuntimeKey: AttributeKey<DynamoDbKtorRuntime> = AttributeKey("DynamoDbKtorRuntime")

/**
 * Ktor application plugin for AWS Kotlin SDK DynamoDB integration.
 *
 * Contract:
 * - Creates missing explicitly registered tables on [ApplicationStarted] when
 *   `autoCreateTables = true`.
 * - Closes only plugin-created clients on [ApplicationStopping].
 * - Stores [DynamoDbKtorRuntime] in application attributes for repository access.
 */
val DynamoDbKtorPlugin: ApplicationPlugin<DynamoDbKtorPluginConfig> = createApplicationPlugin(
    name = "DynamoDbKtorPlugin",
    createConfiguration = ::DynamoDbKtorPluginConfig,
) {
    val runtime = DynamoDbKtorRuntime(pluginConfig.toRuntimeConfig())
    application.attributes.put(DynamoDbKtorRuntimeKey, runtime)

    on(MonitoringEvent(ApplicationStarted)) {
        // Ktor monitoring events are synchronous; table auto-creation is suspend-only AWS Kotlin SDK work.
        runBlocking(Dispatchers.IO) {
            runtime.start()
        }
    }
    on(MonitoringEvent(ApplicationStopping)) {
        // Ktor monitoring events are synchronous; close plugin-owned AWS clients within a bounded suspend bridge.
        runBlocking(Dispatchers.IO) {
            runtime.stop()
        }
    }
}

/**
 * Returns the runtime installed by [DynamoDbKtorPlugin].
 */
fun Application.dynamoDb(): DynamoDbKtorRuntime =
    attributes[DynamoDbKtorRuntimeKey]
