package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.logging.KLogging
import org.springframework.context.SmartLifecycle
import org.springframework.core.env.ConfigurableEnvironment
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** context마다 하나의 executor와 resource마다 하나의 self-rescheduling task를 소유합니다. */
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
internal class AppConfigReloadLifecycle(
    private val sourcesSupplier: () -> List<AppConfigDataPropertySource>,
    private val executorFactory: (Int) -> ScheduledThreadPoolExecutor = ::newExecutor,
    private val initialDelay: (AppConfigDataPropertySource) -> Duration = { it.refreshInterval ?: Duration.ZERO },
    private val randomDelay: (Int) -> Duration = ::fullJitterBackoff,
    private val runtimeClientSupplier: (() -> AppConfigDataSessionClient)? = null,
) : SmartLifecycle {

    constructor(
        environment: ConfigurableEnvironment,
        runtimeClientSupplier: () -> AppConfigDataSessionClient,
    ) : this(
        sourcesSupplier = {
            environment.propertySources
                .mapNotNull { it as? AppConfigDataPropertySource }
        },
        runtimeClientSupplier = runtimeClientSupplier,
    )

    constructor(environment: ConfigurableEnvironment) : this(
        sourcesSupplier = {
            environment.propertySources
                .mapNotNull { it as? AppConfigDataPropertySource }
        },
    )

    companion object: KLogging() {
        private val MAX_BACKOFF: Duration = Duration.ofMinutes(5)
        private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(1)
        private const val MIN_SERVER_POLL_SECONDS = 15L
        private const val MAX_SERVER_POLL_SECONDS = 24L * 60L * 60L
        private const val MAX_POOL_SIZE = 8

        private fun newExecutor(poolSize: Int): ScheduledThreadPoolExecutor =
            ScheduledThreadPoolExecutor(poolSize) { runnable ->
                Thread(runnable, "bluetape4k-appconfig-poller").apply { isDaemon = true }
            }.apply {
                removeOnCancelPolicy = true
                setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
                setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
            }

        private fun fullJitterBackoff(attempt: Int): Duration {
            val exponent = min(attempt.coerceAtLeast(0), 18)
            val capMillis = min(MAX_BACKOFF.toMillis(), INITIAL_BACKOFF.toMillis() shl exponent)
            val millis = if (capMillis <= 1L) 0L else ThreadLocalRandom.current().nextLong(capMillis + 1)
            return Duration.ofMillis(millis.coerceAtLeast(1L))
        }
    }

    private val lifecycleLock = Any()
    private val stopping = AtomicBoolean(false)
    private val futures = ConcurrentHashMap<AppConfigDataPropertySource, ScheduledFuture<*>>()
    private val retryAttempts = ConcurrentHashMap<AppConfigDataPropertySource, Int>()
    private var executor: ScheduledThreadPoolExecutor? = null
    private var runtimeClient: AppConfigDataSessionClient? = null
    private var running = false

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE

    override fun isRunning(): Boolean = synchronized(lifecycleLock) { running }

    override fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            stopping.set(false)
            val sources = sourcesSupplier().distinctBy { it.name }.filter { it.refreshInterval != null }
            if (sources.isEmpty()) {
                running = true
                return
            }
            val createdClient = runtimeClientSupplier?.invoke()
            createdClient?.let { client -> sources.forEach { it.replaceClient(client) } }
            runtimeClient = createdClient
            try {
                val created = executorFactory(min(MAX_POOL_SIZE, maxOf(1, sources.size)))
                executor = created
                sources.forEach { source -> schedule(source, initialDelay(source)) }
                running = true
            } catch (error: RuntimeException) {
                stopping.set(true)
                executor?.let(::cancelAndShutdown)
                executor = null
                runtimeClient?.close()
                runtimeClient = null
                throw error
            }
        }
    }

    override fun stop() {
        val resources = synchronized(lifecycleLock) {
            if (!running && executor == null) return
            stopping.set(true)
            running = false
            val value = executor
            executor = null
            val client = runtimeClient
            runtimeClient = null
            value to client
        }
        resources.first?.let(::cancelAndShutdown)
        resources.second?.close()
    }

    override fun stop(callback: Runnable) {
        try {
            stop()
        } finally {
            callback.run()
        }
    }

    internal fun scheduledResourceCount(): Int = futures.size

    internal fun schedulerPoolSize(): Int = executor?.corePoolSize ?: 0

    internal fun effectivePollDelay(source: AppConfigDataPropertySource): Duration = baseDelay(source)

    private fun schedule(source: AppConfigDataPropertySource, delay: Duration) {
        synchronized(lifecycleLock) {
            if (!stopping.get()) {
                executor?.let { currentExecutor ->
                    val existing = futures[source]
                    if (existing == null || existing.isDone || existing.isCancelled) {
                        val safeDelay = delay.coerceAtLeast(Duration.ZERO)
                        val future = currentExecutor.schedule(
                            {
                                futures.remove(source)
                                pollAndReschedule(source)
                            },
                            safeDelay.toNanos(),
                            TimeUnit.NANOSECONDS,
                        )
                        futures[source] = future
                    }
                }
            }
        }
    }

    private fun pollAndReschedule(source: AppConfigDataPropertySource) {
        if (stopping.get()) return
        var nextDelay = baseDelay(source)
        try {
            val response = fetch(source)
            source.advance(response)
            if (response.configuration.isNotEmpty()) {
                try {
                    val values = AppConfigDataDecoder.decode(
                        payload = response.configuration,
                        contentType = response.contentType,
                        format = source.format,
                        prefix = source.prefix,
                    )
                    source.replace(values)
                } catch (error: RuntimeException) {
                    log.warn(
                        "Keeping previous AppConfig values after decode failure: " +
                            "${source.resource.opaqueIdentity} (${error::class.java.simpleName}).",
                    )
                }
            }
            retryAttempts.remove(source)
            nextDelay = baseDelay(source)
        } catch (_: CancellationException) {
            return
        } catch (error: RuntimeException) {
            source.discardSession()
            val attempt = retryAttempts.merge(source, 1, Int::plus) ?: 1
            nextDelay = randomDelay(attempt)
            log.warn(
                "AppConfig poll failed; retaining previous values and retrying: " +
                    "${source.resource.opaqueIdentity} (${error::class.java.simpleName}).",
            )
        }
        if (!stopping.get()) schedule(source, nextDelay)
    }

    private fun fetch(source: AppConfigDataPropertySource): AppConfigDataResponse {
        val token = source.configurationToken
        if (token != null) {
            return source.client.getLatestConfiguration(token)
        }
        val session = source.client.startConfigurationSession(source.request)
        source.activateSession(session)
        return source.client.getLatestConfiguration(session.initialConfigurationToken)
    }

    private fun baseDelay(source: AppConfigDataPropertySource): Duration {
        val configured = source.refreshInterval ?: Duration.ZERO
        val required = source.requiredMinimumPollInterval
        val server = source.nextPollIntervalSeconds
            ?.takeIf { it in MIN_SERVER_POLL_SECONDS..MAX_SERVER_POLL_SECONDS }
            ?.let(Duration::ofSeconds)
            ?: Duration.ZERO
        return maxOf(configured, required, server)
    }

    private fun cancelAndShutdown(current: ScheduledThreadPoolExecutor) {
        futures.values.forEach { it.cancel(false) }
        futures.clear()
        retryAttempts.clear()
        current.shutdown()
        try {
            if (!current.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                current.shutdownNow()
                current.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            current.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun Duration.coerceAtLeast(other: Duration): Duration = if (this < other) other else this

    private val shutdownTimeoutSeconds = 5L
}
