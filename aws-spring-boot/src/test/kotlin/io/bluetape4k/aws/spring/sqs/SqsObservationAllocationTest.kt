package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SQS 관찰 fast path의 할당량과 invocation 계약을 같은 JVM 안에서 비교합니다.
 *
 * 절대 할당량이 아니라 동일한 business block을 실행하는 direct baseline과 비활성 경로의
 * paired 차이를 사용합니다. bootstrap 표본도 고정 seed를 사용해 회귀 gate를 재현할 수 있습니다.
 */
class SqsObservationAllocationTest {

    private val sentinel = AtomicLong()
    private val disabledContextFactoryCalls = AtomicInteger()
    private val disabledContextFactory: () -> SqsObservationContext = {
        disabledContextFactoryCalls.incrementAndGet()
        processContext()
    }
    private val businessBlock: suspend SqsObservationExecution.() -> Long = {
        sentinel.incrementAndGet()
    }
    private val directExecution = SqsObservationExecution(null, Observation.NOOP)

    @Test
    fun `disabled fast path adds at most half a byte per operation at upper confidence bound`() = runSuspendIO {
        val allocationBean = currentThreadAllocationBean()
        repeat(WARMUP_OPERATIONS) {
            directBaseline()
            disabledFastPath()
        }

        val measuredBefore = sentinel.get()
        val pairedDifferences = DoubleArray(MEASUREMENT_SAMPLES) { sample ->
            val directBytes: Long
            val disabledBytes: Long
            if (sample % 2 == 0) {
                directBytes = measureAllocatedBytes(allocationBean, ::directBaseline)
                disabledBytes = measureAllocatedBytes(allocationBean, ::disabledFastPath)
            } else {
                disabledBytes = measureAllocatedBytes(allocationBean, ::disabledFastPath)
                directBytes = measureAllocatedBytes(allocationBean, ::directBaseline)
            }
            disabledBytes.toDouble() / MEASUREMENT_OPERATIONS -
                directBytes.toDouble() / MEASUREMENT_OPERATIONS
        }
        val expectedInvocations = 2L * MEASUREMENT_OPERATIONS * MEASUREMENT_SAMPLES
        sentinel.get() - measuredBefore shouldBeEqualTo expectedInvocations
        disabledContextFactoryCalls.get() shouldBeEqualTo 0

        val medianDelta = median(pairedDifferences)
        val upperConfidenceBound = bootstrapMedianUpperBound(pairedDifferences)
        println(
            "SQS observation disabled allocation delta: median=$medianDelta B/op, " +
                "upper95=$upperConfidenceBound B/op, samples=$MEASUREMENT_SAMPLES",
        )
        upperConfidenceBound shouldBeLessOrEqualTo MAX_DISABLED_ALLOCATION_DELTA_BYTES
    }

    @Test
    fun `active process emits one observation and one retry event per invocation`() = runSuspendIO {
        val recorder = CountingObservationHandler()
        val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(recorder)
        }
        val runtime = SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
        )
        val before = sentinel.get()

        repeat(ACTIVE_INVOCATIONS) {
            observeSqs(runtime, ::processContext) {
                retry(2)
                retry(3)
                sentinel.incrementAndGet()
            }
        }

        sentinel.get() - before shouldBeEqualTo ACTIVE_INVOCATIONS.toLong()
        recorder.starts.get() shouldBeEqualTo ACTIVE_INVOCATIONS
        recorder.stops.get() shouldBeEqualTo ACTIVE_INVOCATIONS
        recorder.retryEvents.get() shouldBeEqualTo ACTIVE_INVOCATIONS
        recorder.active.get() shouldBeEqualTo 0
        registry.currentObservation.shouldBeNull()
    }

    @Test
    fun `queue sanitizer resolves one stable URL only once`() {
        val sanitizerCalls = AtomicInteger()
        val cache = SqsObservationQueueNameCache { queueUrl ->
            sanitizerCalls.incrementAndGet()
            resolveSqsObservationQueueName(queueUrl)
        }

        repeat(QUEUE_LOOKUPS) {
            cache.resolve(QUEUE_URL) shouldBeEqualTo "orders"
        }

        sanitizerCalls.get() shouldBeEqualTo 1
    }

    private suspend fun directBaseline(): Long = directExecution.businessBlock()

    private suspend fun disabledFastPath(): Long = observeSqs(
        runtime = null,
        contextFactory = disabledContextFactory,
        block = businessBlock,
    )

    private suspend fun measureAllocatedBytes(
        bean: com.sun.management.ThreadMXBean,
        operation: suspend () -> Unit,
    ): Long {
        val threadId = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(MEASUREMENT_OPERATIONS) {
            operation()
        }
        Thread.currentThread().threadId() shouldBeEqualTo threadId
        return (bean.getThreadAllocatedBytes(threadId) - before).coerceAtLeast(0L)
    }

    private fun currentThreadAllocationBean(): com.sun.management.ThreadMXBean {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            ?: error("ThreadMXBean allocated memory is unavailable")
        require(bean.isThreadAllocatedMemorySupported) { "ThreadMXBean allocated memory is unavailable" }
        if (!bean.isThreadAllocatedMemoryEnabled) {
            bean.isThreadAllocatedMemoryEnabled = true
        }
        return bean
    }

    private fun bootstrapMedianUpperBound(values: DoubleArray): Double {
        val random = Random(BOOTSTRAP_SEED)
        val medians = DoubleArray(BOOTSTRAP_RESAMPLES) {
            median(DoubleArray(values.size) { values[random.nextInt(values.size)] })
        }
        medians.sort()
        return medians[((medians.size - 1) * 0.975).toInt()]
    }

    private fun median(values: DoubleArray): Double {
        require(values.isNotEmpty())
        val sorted = values.sortedArray()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun processContext(): SqsObservationContext = SqsObservationContext(
        SqsObservationMetadata(
            listenerId = "allocation-listener",
            queueName = "orders",
            stage = SqsObservationStage.PROCESS,
            batch = false,
            initialAttempt = 1,
            queueNameResolved = true,
        ),
    )

    private class CountingObservationHandler : ObservationHandler<SqsObservationContext> {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val retryEvents = AtomicInteger()
        val active = AtomicInteger()

        override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

        override fun onStart(context: SqsObservationContext) {
            starts.incrementAndGet()
            active.incrementAndGet()
        }

        override fun onEvent(event: Observation.Event, context: SqsObservationContext) {
            if (event.name == "retry") {
                retryEvents.incrementAndGet()
            }
        }

        override fun onStop(context: SqsObservationContext) {
            stops.incrementAndGet()
            active.decrementAndGet()
        }
    }

    private companion object {
        private const val WARMUP_OPERATIONS: Int = 100_000
        private const val MEASUREMENT_OPERATIONS: Int = 1_000_000
        private const val MEASUREMENT_SAMPLES: Int = 30
        private const val BOOTSTRAP_RESAMPLES: Int = 10_000
        private const val BOOTSTRAP_SEED: Long = 473L
        private const val MAX_DISABLED_ALLOCATION_DELTA_BYTES: Double = 0.5
        private const val ACTIVE_INVOCATIONS: Int = 1_000
        private const val QUEUE_LOOKUPS: Int = 10_000
        private const val QUEUE_URL: String = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/orders"
    }
}
