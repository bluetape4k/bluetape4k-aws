package io.bluetape4k.aws.spring.sqs

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 네트워크와 AWS 자격 증명 없이 SQS observation fast path 및 acknowledgement 경합 계약을 측정합니다.
 *
 * 각 invocation은 실제 내부 acknowledgement 구현과 32개 coroutine을 사용하며, teardown에서
 * 외부 I/O 횟수, observation 수명 주기, terminal 상태 및 활성 observation 누수를 검증합니다.
 */
@State(Scope.Thread)
open class SqsObservationBenchmark {

    private lateinit var registry: ObservationRegistry
    private lateinit var recorder: CountingObservationHandler
    private lateinit var runtime: SqsObservationRuntime
    private lateinit var operations: CountingSqsOperations
    private lateinit var singleAcknowledgement: DefaultSqsAcknowledgement
    private lateinit var batchAcknowledgement: DefaultSqsBatchAcknowledgement
    private val sentinel = AtomicLong()
    private val disabledContextFactoryCalls = AtomicInteger()
    private val activeContextFactoryCalls = AtomicInteger()
    private var scenario: Scenario = Scenario.NONE

    @Setup(Level.Invocation)
    fun setupInvocation() {
        sentinel.set(0)
        disabledContextFactoryCalls.set(0)
        activeContextFactoryCalls.set(0)
        scenario = Scenario.NONE
        recorder = CountingObservationHandler()
        registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(recorder)
        }
        runtime = SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
        )
        operations = CountingSqsOperations()
        val messages = (1..BATCH_SIZE).map(::receivedMessage)
        singleAcknowledgement = DefaultSqsAcknowledgement(
            context = SqsListenerInvocationContext(LISTENER_ID, QUEUE_URL, messages.first(), 1),
            operations = operations,
            interceptors = emptyList(),
            observationRuntime = runtime,
        )
        batchAcknowledgement = DefaultSqsBatchAcknowledgement(
            listenerId = LISTENER_ID,
            queueUrl = QUEUE_URL,
            messages = messages,
            operations = operations,
            interceptors = emptyList(),
            observationRuntime = runtime,
        )
    }

    @Benchmark
    fun directBaseline(blackhole: Blackhole) {
        scenario = Scenario.DIRECT
        blackhole.consume(runBlocking { sentinel.incrementAndGet() })
    }

    @Benchmark
    fun disabledFastPath(blackhole: Blackhole) {
        scenario = Scenario.DISABLED
        blackhole.consume(
            runBlocking {
                observeSqs(
                    runtime = null,
                    contextFactory = {
                        disabledContextFactoryCalls.incrementAndGet()
                        processContext()
                    },
                ) {
                    sentinel.incrementAndGet()
                }
            },
        )
    }

    @Benchmark
    fun activeProcess(blackhole: Blackhole) {
        scenario = Scenario.ACTIVE
        blackhole.consume(
            runBlocking {
                observeSqs(
                    runtime = runtime,
                    contextFactory = {
                        activeContextFactoryCalls.incrementAndGet()
                        processContext()
                    },
                ) {
                    sentinel.incrementAndGet()
                }
            },
        )
    }

    @Benchmark
    fun concurrentSingleAck(blackhole: Blackhole) {
        scenario = Scenario.SINGLE_ACK
        blackhole.consume(
            runBlocking {
                runConcurrent {
                    singleAcknowledgement.acknowledge()
                    sentinel.incrementAndGet()
                }
            },
        )
    }

    @Benchmark
    fun concurrentBatchAck(blackhole: Blackhole) {
        scenario = Scenario.BATCH_ACK
        blackhole.consume(
            runBlocking {
                runConcurrent {
                    batchAcknowledgement.acknowledge()
                    sentinel.incrementAndGet()
                }
            },
        )
    }

    @Benchmark
    fun concurrentHeartbeat(blackhole: Blackhole) {
        scenario = Scenario.HEARTBEAT
        blackhole.consume(
            runBlocking {
                runConcurrent {
                    singleAcknowledgement.heartbeat(HEARTBEAT_TIMEOUT_SECONDS) { failure ->
                        throw IllegalStateException("observation cleanup failed", failure)
                    }
                    sentinel.incrementAndGet()
                }
            },
        )
    }

    @TearDown(Level.Invocation)
    fun verifyInvocation() {
        check(!java.lang.Boolean.getBoolean(FORCE_FAILURE_PROPERTY)) {
            "forced benchmark teardown failure"
        }
        require(recorder.active.get() == 0) { "active observations leaked: ${recorder.active.get()}" }
        require(recorder.starts.get() == recorder.stops.get()) {
            "observation starts=${recorder.starts.get()} stops=${recorder.stops.get()}"
        }
        require(registry.currentObservation == null) { "current observation leaked after invocation" }
        when (scenario) {
            Scenario.DIRECT -> {
                require(sentinel.get() == 1L)
                require(recorder.starts.get() == 0)
            }

            Scenario.DISABLED -> {
                require(sentinel.get() == 1L)
                require(disabledContextFactoryCalls.get() == 0)
                require(recorder.starts.get() == 0)
            }

            Scenario.ACTIVE -> {
                require(sentinel.get() == 1L)
                require(activeContextFactoryCalls.get() == 1)
                require(recorder.starts.get() == 1)
                require(recorder.processStarts.get() == 1)
            }

            Scenario.SINGLE_ACK -> {
                require(sentinel.get() == COROUTINE_COUNT.toLong())
                require(singleAcknowledgement.completed)
                require(operations.deleteCalls.get() == 1)
                require(recorder.starts.get() == 1)
                require(recorder.acknowledgementStarts.get() == 1)
            }

            Scenario.BATCH_ACK -> {
                require(sentinel.get() == COROUTINE_COUNT.toLong())
                require(batchAcknowledgement.completed)
                require(batchAcknowledgement.pending.isEmpty())
                require(operations.deleteBatchCalls.get() == 1)
                require(operations.deletedBatchEntries.get() == BATCH_SIZE)
                require(recorder.starts.get() == 1)
                require(recorder.acknowledgementStarts.get() == 1)
            }

            Scenario.HEARTBEAT -> {
                require(sentinel.get() == COROUTINE_COUNT.toLong())
                require(!singleAcknowledgement.completed)
                require(operations.visibilityCalls.get() == COROUTINE_COUNT)
                require(recorder.starts.get() == COROUTINE_COUNT)
                require(recorder.acknowledgementStarts.get() == COROUTINE_COUNT)
            }

            Scenario.NONE -> error("benchmark scenario was not selected")
        }
    }

    private fun processContext(): SqsObservationContext = SqsObservationContext(
        SqsObservationMetadata(
            listenerId = LISTENER_ID,
            queueName = QUEUE_URL,
            stage = SqsObservationStage.PROCESS,
            batch = false,
            initialAttempt = 1,
        ),
    )

    private suspend fun <T> runConcurrent(block: suspend () -> T): List<T> = coroutineScope {
        val ready = AtomicInteger()
        val start = CompletableDeferred<Unit>()
        val jobs = List(COROUTINE_COUNT) {
            async(Dispatchers.Default) {
                ready.incrementAndGet()
                start.await()
                block()
            }
        }
        try {
            withTimeout(BENCHMARK_INVOCATION_TIMEOUT_MILLIS) {
                while (ready.get() < COROUTINE_COUNT) {
                    yield()
                }
                start.complete(Unit)
                jobs.awaitAll().also { results ->
                    require(results.size == COROUTINE_COUNT)
                }
            }
        } finally {
            start.complete(Unit)
            jobs.filterNot { it.isCompleted }.forEach { it.cancel() }
            jobs.joinAll()
            require(jobs.all { it.isCompleted }) { "benchmark coroutines remained incomplete" }
        }
    }

    private class CountingObservationHandler : ObservationHandler<SqsObservationContext> {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val active = AtomicInteger()
        val processStarts = AtomicInteger()
        val acknowledgementStarts = AtomicInteger()

        override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

        override fun onStart(context: SqsObservationContext) {
            starts.incrementAndGet()
            active.incrementAndGet()
            when (context.metadata.stage) {
                SqsObservationStage.PROCESS -> processStarts.incrementAndGet()
                SqsObservationStage.ACKNOWLEDGEMENT -> acknowledgementStarts.incrementAndGet()
                SqsObservationStage.RECEIVE -> Unit
            }
        }

        override fun onStop(context: SqsObservationContext) {
            stops.incrementAndGet()
            active.decrementAndGet()
        }
    }

    private class CountingSqsOperations : SqsOperations {
        val deleteCalls = AtomicInteger()
        val deleteBatchCalls = AtomicInteger()
        val deletedBatchEntries = AtomicInteger()
        val visibilityCalls = AtomicInteger()

        override suspend fun getQueueUrl(queueName: String): String = QUEUE_URL

        override suspend fun createQueue(
            queueName: String,
            attributes: Map<QueueAttributeName, String>,
        ): String = QUEUE_URL

        override suspend fun createConfiguredQueue(queueName: String): String = QUEUE_URL

        override suspend fun send(queueUrl: String, body: String, delaySeconds: Int?): SendMessageResponse =
            SendMessageResponse.builder().messageId("benchmark-message").build()

        override suspend fun receive(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): List<SqsReceivedMessage> = emptyList()

        override suspend fun delete(queueUrl: String, receiptHandle: String): DeleteMessageResponse {
            deleteCalls.incrementAndGet()
            yield()
            return DeleteMessageResponse.builder().build()
        }

        override suspend fun deleteBatch(
            queueUrl: String,
            receiptHandles: Collection<String>,
        ): SqsBatchDeleteResult {
            val handles = receiptHandles.toList()
            deleteBatchCalls.incrementAndGet()
            deletedBatchEntries.addAndGet(handles.size)
            yield()
            return SqsBatchDeleteResult(handles.indices.map { "entry-$it" }, emptyList())
        }

        override suspend fun changeVisibility(
            queueUrl: String,
            receiptHandle: String,
            timeoutSeconds: Int,
        ): ChangeMessageVisibilityResponse {
            visibilityCalls.incrementAndGet()
            yield()
            return ChangeMessageVisibilityResponse.builder().build()
        }

        override fun receiveFlow(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): Flow<SqsReceivedMessage> = emptyFlow()
    }

    private enum class Scenario {
        NONE,
        DIRECT,
        DISABLED,
        ACTIVE,
        SINGLE_ACK,
        BATCH_ACK,
        HEARTBEAT,
    }

    private companion object {
        private const val LISTENER_ID: String = "benchmark-listener"
        private const val QUEUE_URL: String = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/orders"
        private const val BATCH_SIZE: Int = 10
        private const val COROUTINE_COUNT: Int = 32
        private const val HEARTBEAT_TIMEOUT_SECONDS: Int = 30
        private const val BENCHMARK_INVOCATION_TIMEOUT_MILLIS: Long = 30_000L
        private const val FORCE_FAILURE_PROPERTY: String = "bluetape4k.aws.benchmark.forceFailure"

        private fun receivedMessage(index: Int): SqsReceivedMessage = SqsReceivedMessage(
            queueUrl = QUEUE_URL,
            message = Message.builder()
                .messageId("message-$index")
                .receiptHandle("receipt-$index")
                .body("benchmark")
                .build(),
        )
    }
}
