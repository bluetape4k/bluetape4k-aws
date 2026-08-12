package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse
import java.time.Duration
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudWatchLogsKtorRuntimeTest {

    private val operations = mockk<CloudWatchLogsKtorOperations>()
    private val client = mockk<CloudWatchLogsAsyncClient>(relaxed = true)

    @BeforeEach
    fun resetMocks() {
        clearMocks(operations, client)
    }

    @Test
    fun `append requires default log stream`() = runSuspendIO {
        val runtime = CloudWatchLogsKtorRuntime(operations = operations)

        assertFailsWith<IllegalArgumentException> {
            runtime.append("message")
        }
    }

    @Test
    fun `flush sends sorted buffered events once`() = runSuspendIO {
        val captured = mutableListOf<List<InputLogEvent>>()
        coEvery { operations.putLogEvents(any(), any()) } coAnswers {
            captured += secondArg<List<InputLogEvent>>()
            listOf(PutLogEventsResponse.builder().build())
        }
        val runtime = runtime()

        runtime.append("third", Instant.ofEpochMilli(3))
        runtime.append("first", Instant.ofEpochMilli(1))
        runtime.append("second", Instant.ofEpochMilli(2))

        runtime.flush().size shouldBeEqualTo 2
        runtime.flush() shouldBeEqualTo emptyList()
        captured.map { it.size } shouldBeEqualTo listOf(2, 1)
        captured.flatten().map { it.message() } shouldBeEqualTo listOf("first", "second", "third")
    }

    @Test
    fun `concurrent flush does not duplicate events`() = runSuspendIO {
        val captured = mutableListOf<List<InputLogEvent>>()
        coEvery { operations.putLogEvents(any(), any()) } coAnswers {
            delay(50)
            captured += secondArg<List<InputLogEvent>>()
            listOf(PutLogEventsResponse.builder().build())
        }
        val runtime = runtime()
        repeat(5) { index ->
            runtime.append("message-$index", Instant.ofEpochMilli(index.toLong()))
        }

        listOf(
            async { runtime.flush() },
            async { runtime.flush() },
        ).joinAll()

        captured.flatten().map { it.message() }.sorted() shouldBeEqualTo
            listOf("message-0", "message-1", "message-2", "message-3", "message-4")
    }

    @Test
    fun `stop flushes buffered events and closes owned client once`() = runSuspendIO {
        coEvery { operations.putLogEvents(any(), any()) } returns listOf(PutLogEventsResponse.builder().build())
        val runtime = runtime(ownedClient = client)

        runtime.append("message", Instant.EPOCH)
        runtime.stop()
        runtime.stop()

        coVerify(exactly = 1) { operations.putLogEvents(any(), any()) }
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `stop reports timeout pending events and closes owned client`() = runSuspendIO {
        val order = mutableListOf<String>()
        val observations = mutableListOf<CloudWatchLogsShutdownObservation>()
        every { client.close() } answers { order += "close" }
        coEvery { operations.putLogEvents(any(), any()) } coAnswers {
            order += "flush"
            delay(250)
            listOf(PutLogEventsResponse.builder().build())
        }
        val runtime = runtime(
            ownedClient = client,
            shutdownFlushTimeout = Duration.ofMillis(10),
            shutdownObservers = listOf(CloudWatchLogsShutdownObserver { observations += it }),
        )

        runtime.append("message", Instant.EPOCH)
        runtime.stop()

        observations.single().outcome shouldBeEqualTo CloudWatchLogsShutdownOutcome.Timeout
        observations.single().pendingEventCount shouldBeEqualTo 1
        observations.single().droppedEventCount shouldBeEqualTo 1
        runtime.pendingEventCount() shouldBeEqualTo 1
        order shouldBeEqualTo listOf("flush", "close")
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `strict shutdown timeout throws after reporting and closing`() = runSuspendIO {
        val order = mutableListOf<String>()
        val observations = mutableListOf<CloudWatchLogsShutdownObservation>()
        every { client.close() } answers { order += "close" }
        coEvery { operations.putLogEvents(any(), any()) } coAnswers {
            order += "flush"
            delay(250)
            listOf(PutLogEventsResponse.builder().build())
        }
        val runtime = runtime(
            ownedClient = client,
            shutdownFlushTimeout = Duration.ofMillis(10),
            shutdownPolicy = CloudWatchLogsShutdownPolicy.ThrowOnTimeout,
            shutdownObservers = listOf(CloudWatchLogsShutdownObserver { observations += it }),
        )

        runtime.append("message", Instant.EPOCH)
        val error = assertFailsWith<CloudWatchLogsShutdownTimeoutException> {
            runtime.stop()
        }

        error.pendingEventCount shouldBeEqualTo 1
        observations.single().outcome shouldBeEqualTo CloudWatchLogsShutdownOutcome.Timeout
        order shouldBeEqualTo listOf("flush", "close")
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `stop reports cancellation restores pending events and closes client`() = runSuspendIO {
        val order = mutableListOf<String>()
        val observations = mutableListOf<CloudWatchLogsShutdownObservation>()
        every { client.close() } answers { order += "close" }
        coEvery { operations.putLogEvents(any(), any()) } coAnswers {
            order += "flush"
            delay(250)
            listOf(PutLogEventsResponse.builder().build())
        }
        val runtime = runtime(
            ownedClient = client,
            shutdownObservers = listOf(CloudWatchLogsShutdownObserver { observations += it }),
        )

        runtime.append("message", Instant.EPOCH)
        val stopping = async { runtime.stop() }
        delay(20)
        stopping.cancelAndJoin()

        observations.single().outcome shouldBeEqualTo CloudWatchLogsShutdownOutcome.Cancelled
        observations.single().pendingEventCount shouldBeEqualTo 1
        runtime.pendingEventCount() shouldBeEqualTo 1
        order shouldBeEqualTo listOf("flush", "close")
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `started stop cancellation still closes client`() = runSuspendIO {
        val order = mutableListOf<String>()
        val observations = mutableListOf<CloudWatchLogsShutdownObservation>()
        every { client.close() } answers { order += "close" }
        coEvery { operations.putLogEvents(any(), any()) } coAnswers {
            order += "flush"
            delay(250)
            listOf(PutLogEventsResponse.builder().build())
        }
        val runtime = runtime(
            ownedClient = client,
            flushInterval = Duration.ofSeconds(5),
            shutdownObservers = listOf(CloudWatchLogsShutdownObserver { observations += it }),
        )

        runtime.start()
        runtime.append("message", Instant.EPOCH)
        val stopping = async { runtime.stop() }
        delay(20)
        stopping.cancelAndJoin()

        observations.single().outcome shouldBeEqualTo CloudWatchLogsShutdownOutcome.Cancelled
        runtime.pendingEventCount() shouldBeEqualTo 1
        order shouldBeEqualTo listOf("flush", "close")
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `stop reports flush failure restores pending events and closes client`() = runSuspendIO {
        val order = mutableListOf<String>()
        val observations = mutableListOf<CloudWatchLogsShutdownObservation>()
        val failure = IllegalStateException("flush failed")
        every { client.close() } answers { order += "close" }
        coEvery { operations.putLogEvents(any(), any()) } coAnswers {
            order += "flush"
            throw failure
        }
        val runtime = runtime(
            ownedClient = client,
            shutdownObservers = listOf(CloudWatchLogsShutdownObserver { observations += it }),
        )

        runtime.append("message", Instant.EPOCH)
        val error = assertFailsWith<IllegalStateException> {
            runtime.stop()
        }

        error.message shouldBeEqualTo failure.message
        observations.single().outcome shouldBeEqualTo CloudWatchLogsShutdownOutcome.Failure
        observations.single().pendingEventCount shouldBeEqualTo 1
        observations.single().droppedEventCount shouldBeEqualTo 1
        runtime.pendingEventCount() shouldBeEqualTo 1
        order shouldBeEqualTo listOf("flush", "close")
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `successful stop reports zero pending events before client close`() = runSuspendIO {
        val order = mutableListOf<String>()
        val observations = mutableListOf<CloudWatchLogsShutdownObservation>()
        every { client.close() } answers { order += "close" }
        coEvery { operations.putLogEvents(any(), any()) } coAnswers {
            order += "flush"
            listOf(PutLogEventsResponse.builder().build())
        }
        val runtime = runtime(
            ownedClient = client,
            shutdownObservers = listOf(CloudWatchLogsShutdownObserver { observations += it }),
        )

        runtime.append("message", Instant.EPOCH)
        runtime.stop()

        observations.single().outcome shouldBeEqualTo CloudWatchLogsShutdownOutcome.Success
        observations.single().pendingEventCount shouldBeEqualTo 0
        observations.single().droppedEventCount shouldBeEqualTo 0
        order shouldBeEqualTo listOf("flush", "close")
    }

    @Test
    fun `startup setup is opt in`() = runSuspendIO {
        coEvery { operations.createLogGroup(any()) } returns mockk()
        coEvery { operations.createLogStream(any()) } returns mockk()
        val runtime = runtime(
            createLogGroupOnStart = true,
            createLogStreamOnStart = true,
            flushInterval = Duration.ofMillis(500),
        )

        runtime.start()
        runtime.stop()

        coVerify(exactly = 1) { operations.createLogGroup("/app/test") }
        coVerify(exactly = 1) { operations.createLogStream(logStream()) }
    }

    @Test
    fun `startup setup failure closes owned client`() = runSuspendIO {
        coEvery { operations.createLogGroup(any()) } throws IllegalStateException("boom")
        val runtime = runtime(
            ownedClient = client,
            createLogGroupOnStart = true,
        )

        assertFailsWith<IllegalStateException> {
            runtime.start()
        }

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `periodic flush continues after transient failure`() = runSuspendIO {
        coEvery { operations.putLogEvents(any(), any()) } throws IllegalStateException("boom") andThen
            listOf(PutLogEventsResponse.builder().build())
        val runtime = runtime(flushInterval = Duration.ofMillis(20))

        runtime.append("message", Instant.EPOCH)
        runtime.start()
        delay(120)
        runtime.stop()

        coVerify(atLeast = 2) { operations.putLogEvents(any(), any()) }
    }

    @Test
    fun `flush propagates cancellation and restores buffered events`() = runSuspendIO {
        coEvery { operations.putLogEvents(any(), any()) } throws CancellationException("cancelled")
        val runtime = runtime()
        runtime.append("message", Instant.EPOCH)

        assertFailsWith<CancellationException> {
            runtime.flush()
        }

        assertFailsWith<CancellationException> {
            runtime.flush()
        }
        coVerify(exactly = 2) { operations.putLogEvents(any(), any()) }
    }

    @Test
    fun `empty flush does not call operations`() = runSuspendIO {
        runtime().flush() shouldBeEqualTo emptyList()

        coVerify(exactly = 0) { operations.putLogEvents(any(), any()) }
    }

    @Test
    fun `log stream value object rejects blanks`() {
        val error = assertFailsWith<IllegalArgumentException> {
            CloudWatchLogStream(" ", "stream")
        }

        error.message.orEmpty().contains("logGroupName").shouldBeTrue()
    }

    private fun runtime(
        ownedClient: CloudWatchLogsAsyncClient? = null,
        flushInterval: Duration = Duration.ofSeconds(5),
        shutdownFlushTimeout: Duration = Duration.ofSeconds(5),
        shutdownPolicy: CloudWatchLogsShutdownPolicy = CloudWatchLogsShutdownPolicy.WarnAndContinue,
        shutdownObservers: List<CloudWatchLogsShutdownObserver> = emptyList(),
        createLogGroupOnStart: Boolean = false,
        createLogStreamOnStart: Boolean = false,
    ): CloudWatchLogsKtorRuntime =
        CloudWatchLogsKtorRuntime(
            operations = operations,
            ownedClient = ownedClient,
            logStream = logStream(),
            batchSize = 2,
            flushInterval = flushInterval,
            shutdownFlushTimeout = shutdownFlushTimeout,
            shutdownPolicy = shutdownPolicy,
            shutdownObservers = shutdownObservers,
            createLogGroupOnStart = createLogGroupOnStart,
            createLogStreamOnStart = createLogStreamOnStart,
        )

    private fun logStream(): CloudWatchLogStream =
        CloudWatchLogStream("/app/test", "stream-1")
}
