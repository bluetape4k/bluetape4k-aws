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
 * [AwsExposedPlugin]이 설치하는 런타임입니다.
 *
 * ## 계약
 *
 * 런타임은 Ktor 애플리케이션 시작 시 생성하고 종료 시 닫는 [AwsExposedDatabaseRegistry] 하나를
 * 소유합니다. 경로 코드는 요청마다 데이터베이스 팩토리를 생성하지 말고 [handle], [database],
 * [transaction]을 사용해야 합니다.
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
     * 시작된 레지스트리입니다. 플러그인이 시작을 완료하지 않았으면 예외를 던집니다.
     */
    val registry: AwsExposedDatabaseRegistry
        get() = registryRef.value ?: throw IllegalStateException("AwsExposedPlugin is not started.")

    /**
     * 런타임을 시작하고 공유 레지스트리를 생성합니다.
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
     * 런타임을 중지하고 레지스트리를 한 번 닫습니다.
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
     * 기본 또는 이름이 지정된 데이터베이스 핸들을 반환합니다.
     */
    fun handle(name: String? = null): AwsExposedDatabaseHandle =
        registry.get(name)

    /**
     * 기본 또는 이름이 지정된 Exposed [Database]를 반환합니다.
     */
    fun database(name: String? = null): Database =
        handle(name).database

    /**
     * Exposed JDBC suspend 트랜잭션 안에서 [statement]를 실행합니다.
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
