package io.bluetape4k.aws.ktor.exposed

import io.bluetape4k.aws.exposed.AwsExposedDatabaseHandle
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.coroutines.CoroutineContext

/**
 * Application attribute key that stores the installed [AwsExposedKtorRuntime].
 */
val AwsExposedKtorRuntimeKey: AttributeKey<AwsExposedKtorRuntime> = AttributeKey("AwsExposedKtorRuntime")

/**
 * Ktor application plugin for AWS-backed Exposed JDBC databases.
 *
 * ## Contract
 *
 * The plugin creates a shared [AwsExposedKtorRuntime] on application start,
 * stores it in application attributes, and closes its registry on application
 * stopping. Ktor lifecycle hooks are synchronous, so startup and shutdown bridge
 * to suspend database work with bounded `runBlocking(Dispatchers.IO)`.
 */
val AwsExposedPlugin: ApplicationPlugin<AwsExposedPluginConfig> = createApplicationPlugin(
    name = "AwsExposedPlugin",
    createConfiguration = ::AwsExposedPluginConfig,
) {
    val runtime = AwsExposedKtorRuntime(pluginConfig.toRuntimeConfig())
    application.attributes.put(AwsExposedKtorRuntimeKey, runtime)

    on(MonitoringEvent(ApplicationStarted)) {
        // Ktor monitoring events are synchronous; registry creation is suspend and may initialize blocking JDBC pools.
        runBlocking(Dispatchers.IO) {
            runtime.start()
        }
    }
    on(MonitoringEvent(ApplicationStopping)) {
        // Ktor monitoring events are synchronous; registry shutdown closes blocking JDBC resources.
        runBlocking(Dispatchers.IO) {
            runtime.stop()
        }
    }
}

/**
 * Returns the runtime installed by [AwsExposedPlugin].
 *
 * @throws IllegalStateException when [AwsExposedPlugin] is not installed.
 */
fun Application.awsExposed(): AwsExposedKtorRuntime =
    attributes.getOrNull(AwsExposedKtorRuntimeKey)
        ?: throw IllegalStateException("AwsExposedPlugin is not installed.")

/**
 * Returns the runtime installed by [AwsExposedPlugin] for this call.
 *
 * @throws IllegalStateException when [AwsExposedPlugin] is not installed.
 */
fun ApplicationCall.awsExposed(): AwsExposedKtorRuntime =
    application.awsExposed()

/**
 * Returns the default or named AWS-backed Exposed database handle.
 *
 * @throws IllegalStateException when [AwsExposedPlugin] is not installed or not started.
 */
fun Application.awsExposedHandle(name: String? = null): AwsExposedDatabaseHandle =
    awsExposed().handle(name)

/**
 * Returns the default or named AWS-backed Exposed database handle for this call.
 *
 * @throws IllegalStateException when [AwsExposedPlugin] is not installed or not started.
 */
fun ApplicationCall.awsExposedHandle(name: String? = null): AwsExposedDatabaseHandle =
    awsExposed().handle(name)

/**
 * Returns the default or named Exposed [Database].
 *
 * @throws IllegalStateException when [AwsExposedPlugin] is not installed or not started.
 */
fun Application.awsExposedDatabase(name: String? = null): Database =
    awsExposed().database(name)

/**
 * Returns the default or named Exposed [Database] for this call.
 *
 * @throws IllegalStateException when [AwsExposedPlugin] is not installed or not started.
 */
fun ApplicationCall.awsExposedDatabase(name: String? = null): Database =
    awsExposed().database(name)

/**
 * Runs [statement] inside an Exposed JDBC suspend transaction.
 *
 * @throws IllegalStateException when [AwsExposedPlugin] is not installed or not started.
 */
suspend fun <T> Application.awsExposedTransaction(
    name: String? = null,
    context: CoroutineContext? = null,
    statement: suspend JdbcTransaction.() -> T,
): T =
    if (context == null) {
        awsExposed().transaction(name = name, statement = statement)
    } else {
        awsExposed().transaction(name = name, context = context, statement = statement)
    }

/**
 * Runs [statement] inside an Exposed JDBC suspend transaction for this call.
 *
 * @throws IllegalStateException when [AwsExposedPlugin] is not installed or not started.
 */
suspend fun <T> ApplicationCall.awsExposedTransaction(
    name: String? = null,
    context: CoroutineContext? = null,
    statement: suspend JdbcTransaction.() -> T,
): T =
    if (context == null) {
        awsExposed().transaction(name = name, statement = statement)
    } else {
        awsExposed().transaction(name = name, context = context, statement = statement)
    }
