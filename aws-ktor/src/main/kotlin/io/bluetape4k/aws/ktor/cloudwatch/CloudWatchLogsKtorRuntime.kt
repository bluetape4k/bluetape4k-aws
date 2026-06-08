package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.aws.cloudwatch.model.cloudwatchlogs.inputLogEventOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireInRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime holder for Ktor CloudWatch Logs operations, buffering, and client lifecycle.
 *
 * ## Contract
 *
 * Publishing is explicit: events are sent only after application code appends
 * events or calls operations directly. Shutdown flush is bounded by
 * [shutdownFlushTimeout].
 */
class CloudWatchLogsKtorRuntime(
    val operations: CloudWatchLogsKtorOperations,
    private val ownedClient: CloudWatchLogsAsyncClient? = null,
    private val logStream: CloudWatchLogStream? = null,
    private val batchSize: Int = CLOUDWATCH_LOGS_MAX_BATCH_SIZE,
    private val flushInterval: Duration = Duration.ofSeconds(5),
    private val shutdownFlushTimeout: Duration = Duration.ofSeconds(5),
    private val createLogGroupOnStart: Boolean = false,
    private val createLogStreamOnStart: Boolean = false,
) {
    companion object: KLogging()

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val bufferMutex = Mutex()
    private val buffer = mutableListOf<InputLogEvent>()

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var flushJob: Job? = null

    init {
        batchSize.requireInRange(CLOUDWATCH_LOGS_MIN_BATCH_SIZE, CLOUDWATCH_LOGS_MAX_BATCH_SIZE, "batchSize")
        require(!flushInterval.isNegative && !flushInterval.isZero) {
            "flushInterval must be positive."
        }
        require(!shutdownFlushTimeout.isNegative && !shutdownFlushTimeout.isZero) {
            "shutdownFlushTimeout must be positive."
        }
    }

    /**
     * Starts optional setup and periodic flushing.
     */
    suspend fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        try {
            val startupLogStream = if (createLogGroupOnStart || createLogStreamOnStart) {
                requireNotNull(logStream) {
                    "logGroupName and logStreamName must be configured for startup setup."
                }
            } else {
                null
            }
            if (createLogGroupOnStart) {
                operations.createLogGroup(requireNotNull(startupLogStream).logGroupName)
            }
            if (createLogStreamOnStart) {
                operations.createLogStream(requireNotNull(startupLogStream))
            }

            val currentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("cloudwatch-logs"))
            scope = currentScope
            flushJob = currentScope.launch(CoroutineName("cloudwatch-logs-flush")) {
                while (isActive) {
                    delay(flushInterval.toMillis())
                    try {
                        flush()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(e) { "CloudWatch Logs periodic flush failed. Events were restored to buffer." }
                    }
                }
            }
        } catch (e: CancellationException) {
            started.set(false)
            closeOwnedClient()
            throw e
        } catch (e: Exception) {
            started.set(false)
            closeOwnedClient()
            throw e
        }
    }

    /**
     * Appends a log [message] to the configured default log stream buffer.
     */
    suspend fun append(
        message: String,
        timestamp: Instant = Instant.now(),
    ) {
        append(
            inputLogEventOf(
                timestamp = timestamp.toEpochMilli(),
                message = message,
            )
        )
    }

    /**
     * Appends [logEvent] to the configured default log stream buffer.
     */
    suspend fun append(logEvent: InputLogEvent) {
        requireNotNull(logStream) {
            "logGroupName and logStreamName must be configured for buffered publishing."
        }
        bufferMutex.withLock {
            buffer += logEvent
        }
    }

    /**
     * Flushes buffered events to CloudWatch Logs.
     */
    suspend fun flush(): List<PutLogEventsResponse> {
        val drained = bufferMutex.withLock {
            if (buffer.isEmpty()) {
                return emptyList()
            }
            buffer
                .sortedBy { it.timestamp() ?: 0L }
                .also { buffer.clear() }
        }

        return try {
            val targetLogStream = requireNotNull(logStream)
            drained.chunked(batchSize).flatMap { batch ->
                operations.putLogEvents(targetLogStream, batch)
            }
        } catch (e: CancellationException) {
            restore(drained)
            throw e
        } catch (e: Exception) {
            restore(drained)
            throw e
        }
    }

    /**
     * Stops periodic flushing, flushes remaining events, and closes plugin-owned clients.
     */
    suspend fun stop() {
        if (started.compareAndSet(true, false)) {
            flushJob?.cancelAndJoin()
            flushJob = null
            scope?.cancel()
            scope = null
        }

        try {
            withTimeoutOrNull(shutdownFlushTimeout.toMillis()) {
                flush()
            }
        } finally {
            closeOwnedClient()
        }
    }

    private suspend fun restore(events: List<InputLogEvent>) {
        bufferMutex.withLock {
            buffer.addAll(0, events)
        }
    }

    private suspend fun closeOwnedClient() {
        if (closed.compareAndSet(false, true)) {
            ownedClient?.let { client ->
                withContext(Dispatchers.IO) {
                    runInterruptible {
                        client.close()
                    }
                }
            }
        }
    }
}
