package io.bluetape4k.aws.ktor.exposed

import io.bluetape4k.aws.exposed.AwsDatabaseProperties
import io.bluetape4k.aws.exposed.AwsDatabaseSettingsResolver
import io.bluetape4k.aws.exposed.AwsExposedDatabaseHandle
import io.bluetape4k.aws.exposed.AwsExposedDatabaseRegistry
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

internal class AwsExposedKtorRuntimeConfig(
    val databaseProperties: AwsDatabaseProperties,
    val registryFactory: suspend (
        AwsDatabaseProperties,
        AwsDatabaseSettingsResolver,
    ) -> AwsExposedDatabaseRegistry,
    val settingsResolver: AwsDatabaseSettingsResolver,
    val transactionContext: CoroutineContext,
    val startTimeout: Duration,
    val stopTimeout: Duration,
)

/**
 * Runtime installed by [AwsExposedPlugin].
 *
 * ## Contract
 *
 * The runtime owns one [AwsExposedDatabaseRegistry] created during Ktor
 * application startup and closed during shutdown. Route code should use
 * [handle], [database], or [transaction] instead of creating per-request
 * database factories.
 */
class AwsExposedKtorRuntime internal constructor(
    private val config: AwsExposedKtorRuntimeConfig,
) {

    private enum class LifecycleState {
        NEW,
        STARTING,
        STARTED,
        STOPPING,
        STOPPED,
    }

    private val state = atomic(LifecycleState.NEW)
    private val registryRef = atomic<AwsExposedDatabaseRegistry?>(null)

    /**
     * Started registry. Throws when the plugin has not completed startup.
     */
    val registry: AwsExposedDatabaseRegistry
        get() = registryRef.value ?: throw IllegalStateException("AwsExposedPlugin is not started.")

    /**
     * Starts the runtime and creates the shared registry.
     */
    suspend fun start() {
        if (!state.compareAndSet(LifecycleState.NEW, LifecycleState.STARTING)) {
            when (state.value) {
                LifecycleState.STARTED -> return
                LifecycleState.STARTING -> return
                LifecycleState.NEW -> return
                LifecycleState.STOPPING,
                LifecycleState.STOPPED,
                -> throw IllegalStateException("AwsExposedPlugin cannot be started after it has stopped.")
            }
        }

        try {
            val created = withLifecycleTimeout(
                timeout = config.startTimeout,
                timeoutMessage = "Timed out while starting AwsExposedPlugin after ${config.startTimeout}.",
            ) {
                config.registryFactory(config.databaseProperties, config.settingsResolver)
            }
            registryRef.value = created
            state.value = LifecycleState.STARTED
        } catch (e: CancellationException) {
            state.value = LifecycleState.STOPPED
            throw e
        } catch (e: Exception) {
            state.value = LifecycleState.STOPPED
            throw e
        }
    }

    /**
     * Stops the runtime and closes the registry once.
     */
    suspend fun stop() {
        val closeable = when (state.value) {
            LifecycleState.NEW -> {
                state.compareAndSet(LifecycleState.NEW, LifecycleState.STOPPED)
                null
            }
            LifecycleState.STARTING -> null
            LifecycleState.STARTED -> {
                if (state.compareAndSet(LifecycleState.STARTED, LifecycleState.STOPPING)) {
                    registryRef.getAndSet(null)
                } else {
                    null
                }
            }
            LifecycleState.STOPPING,
            LifecycleState.STOPPED,
            -> null
        } ?: return

        try {
            withTimeout(config.stopTimeout) {
                runInterruptible(Dispatchers.IO) {
                    closeable.close()
                }
            }
        } catch (e: TimeoutCancellationException) {
            log.warn(e) { "AwsExposedPlugin registry close did not finish within ${config.stopTimeout}." }
        } catch (e: Exception) {
            if (e.hasCause<InterruptedException>()) {
                log.warn(e) { "AwsExposedPlugin registry close was interrupted after ${config.stopTimeout}." }
            } else {
                throw e
            }
        } finally {
            state.value = LifecycleState.STOPPED
        }
    }

    /**
     * Returns the default or named database handle.
     */
    fun handle(name: String? = null): AwsExposedDatabaseHandle =
        registry.get(name)

    /**
     * Returns the default or named Exposed [Database].
     */
    fun database(name: String? = null): Database =
        handle(name).database

    /**
     * Runs [statement] inside an Exposed JDBC suspend transaction.
     */
    suspend fun <T> transaction(
        name: String? = null,
        context: CoroutineContext = config.transactionContext,
        statement: suspend JdbcTransaction.() -> T,
    ): T =
        withContext(context) {
            suspendTransaction(db = database(name)) {
                statement()
            }
        }

    private suspend fun <T> withLifecycleTimeout(
        timeout: Duration,
        timeoutMessage: String,
        block: suspend () -> T,
    ): T =
        try {
            withTimeout(timeout) {
                block()
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException(timeoutMessage, e)
        }

    private inline fun <reified T: Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) {
                return true
            }
            current = current.cause
        }
        return false
    }

    companion object: KLogging()
}
