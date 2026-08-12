package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.aws.cloudwatch.model.cloudwatchlogs.inputLogEventOf
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireInRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
 * Ktor CloudWatch Logs 작업, 버퍼링, 클라이언트 수명 주기를 보관하는 런타임입니다.
 *
 * ## 계약
 *
 * 게시 작업은 명시적입니다. 애플리케이션 코드가 이벤트를 추가하거나 작업을 직접 호출한 뒤에만
 * 이벤트를 전송합니다. 종료 시 flush 시간은 [shutdownFlushTimeout]으로 제한되며,
 * 기본 [CloudWatchLogsShutdownPolicy.WarnAndContinue] 정책은 timeout을 관찰 이벤트와 warning으로
 * 남기고 종료를 계속합니다. [CloudWatchLogsShutdownPolicy.ThrowOnTimeout]은 owned client를 닫은
 * 뒤 [CloudWatchLogsShutdownTimeoutException]을 전파합니다.
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
    private val shutdownPolicy: CloudWatchLogsShutdownPolicy = CloudWatchLogsShutdownPolicy.WarnAndContinue,
    shutdownObservers: List<CloudWatchLogsShutdownObserver> = emptyList(),
) {
    companion object: KLoggingChannel()

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val bufferMutex = Mutex()
    private val buffer = mutableListOf<InputLogEvent>()
    private val shutdownObservers = shutdownObservers.toList()

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var flushJob: Job? = null

    /** 이전 버전의 생성자와 호환되는 기본 shutdown 정책 생성자입니다. */
    constructor(
        operations: CloudWatchLogsKtorOperations,
        ownedClient: CloudWatchLogsAsyncClient?,
        logStream: CloudWatchLogStream?,
        batchSize: Int,
        flushInterval: Duration,
        shutdownFlushTimeout: Duration,
        createLogGroupOnStart: Boolean,
        createLogStreamOnStart: Boolean,
    ) : this(
        operations,
        ownedClient,
        logStream,
        batchSize,
        flushInterval,
        shutdownFlushTimeout,
        createLogGroupOnStart,
        createLogStreamOnStart,
        CloudWatchLogsShutdownPolicy.WarnAndContinue,
        emptyList(),
    )

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
     * 선택적인 설정과 주기적 flush를 시작합니다.
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
                    delay(timeMillis = flushInterval.toMillis())
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
     * 로그 [message]를 구성된 기본 로그 스트림 버퍼에 추가합니다.
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
     * [logEvent]를 구성된 기본 로그 스트림 버퍼에 추가합니다.
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
     * 버퍼링한 이벤트를 CloudWatch Logs로 flush합니다.
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
     * 현재 buffer에 남은 CloudWatch Logs event 수를 반환합니다.
     */
    suspend fun pendingEventCount(): Int =
        bufferMutex.withLock { buffer.size }

    /**
     * 주기적 flush를 중지하고 남은 이벤트를 전송한 뒤 플러그인이 소유한 클라이언트를 닫습니다.
     */
    suspend fun stop() {
        var outcome = CloudWatchLogsShutdownOutcome.Success
        var cause: Throwable? = null
        var failure: Throwable? = null
        var observation: CloudWatchLogsShutdownObservation? = null

        try {
            if (started.compareAndSet(true, false)) {
                try {
                    flushJob?.cancelAndJoin()
                } finally {
                    flushJob = null
                    scope?.cancel()
                    scope = null
                }
            }

            val flushResult = withTimeoutOrNull(timeMillis = shutdownFlushTimeout.toMillis()) {
                flush()
            }
            if (flushResult == null) {
                outcome = CloudWatchLogsShutdownOutcome.Timeout
            }
        } catch (e: CancellationException) {
            outcome = CloudWatchLogsShutdownOutcome.Cancelled
            cause = e
            failure = e
        } catch (e: Exception) {
            outcome = CloudWatchLogsShutdownOutcome.Failure
            cause = e
            failure = e
        } finally {
            withContext(NonCancellable) {
                val pendingEventCount = pendingEventCount()
                val shutdownObservation = CloudWatchLogsShutdownObservation(
                    outcome = outcome,
                    pendingEventCount = pendingEventCount,
                    droppedEventCount = if (outcome == CloudWatchLogsShutdownOutcome.Success) {
                        0
                    } else {
                        pendingEventCount
                    },
                    cause = cause,
                )
                observation = shutdownObservation
                reportShutdown(shutdownObservation)
                closeOwnedClient()
            }
        }

        failure?.let { throw it }
        val completedObservation = requireNotNull(observation)
        if (completedObservation.outcome == CloudWatchLogsShutdownOutcome.Timeout &&
            shutdownPolicy == CloudWatchLogsShutdownPolicy.ThrowOnTimeout
        ) {
            throw CloudWatchLogsShutdownTimeoutException(
                timeout = shutdownFlushTimeout,
                pendingEventCount = completedObservation.pendingEventCount,
            )
        }
    }

    private suspend fun restore(events: List<InputLogEvent>) {
        bufferMutex.withLock {
            buffer.addAll(0, events)
        }
    }

    private suspend fun closeOwnedClient() {
        withContext(NonCancellable) {
            if (closed.compareAndSet(false, true)) {
                ownedClient?.let { client ->
                    runInterruptible(Dispatchers.IO) {
                        client.close()
                    }
                }
            }
        }
    }

    private fun reportShutdown(observation: CloudWatchLogsShutdownObservation) {
        if (observation.outcome != CloudWatchLogsShutdownOutcome.Success) {
            val message = "CloudWatch Logs shutdown flush ${observation.outcome.name.lowercase()} " +
                "with ${observation.pendingEventCount} pending event(s); " +
                "${observation.droppedEventCount} event(s) will not be retried."
            observation.cause?.let { cause ->
                log.warn(cause) { message }
            } ?: log.warn { message }
        }

        shutdownObservers.forEach { observer ->
            runCatching { observer.observe(observation) }
                .onFailure { error ->
                    log.warn(error) { "CloudWatch Logs shutdown observer failed." }
                }
        }
    }
}
