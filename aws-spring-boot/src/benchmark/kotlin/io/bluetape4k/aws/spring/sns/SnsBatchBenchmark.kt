package io.bluetape4k.aws.spring.sns

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.openjdk.jmh.annotations.AuxCounters
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import software.amazon.awssdk.services.sns.model.PublishBatchResponse
import software.amazon.awssdk.services.sns.model.PublishBatchResultEntry
import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 네트워크·자격 증명 없이 SNS 배치 실행기의 비교 가능한 기준선을 수집합니다.
 * 실제 AWS 지연과 서비스 비용은 이 fake publisher 결과에 포함하지 않습니다.
 */
@State(Scope.Benchmark)
open class SnsBatchBenchmark {

    @Param("1", "10", "11", "20", "21", "100")
    var entryCount: Int = 0

    @Param("1", "2", "4")
    var maxInFlightBatches: Int = 0

    @Param("success", "transport")
    var scenario: String = ""

    private lateinit var request: SnsPublishBatchRequest

    @Setup(Level.Trial)
    fun setup() {
        request = SnsPublishBatchRequest(
            topicArn = "arn:aws:sns:us-east-1:000000000000:benchmark-topic",
            entries = (1..entryCount).map { index ->
                SnsPublishBatchEntry(
                    id = "entry-$index",
                    message = "message-$index",
                )
            },
        )
    }

    @Benchmark
    fun publishBatch(counters: SnsBatchBenchmarkCounters, blackhole: Blackhole) {
        val result = runBlocking {
            try {
                SnsBatchExecutor(
                    publishChunk = { _, entries ->
                        counters.recordStart(entries.size)
                        try {
                            yield()
                            if (scenario == "transport" && entries.first().id == "entry-1") {
                                throw IllegalStateException("synthetic-transport-failure")
                            }
                            successResponseFor(entries)
                        } finally {
                            counters.recordCompletion(entries.size)
                        }
                    },
                    onCompletedEntryIds = counters::recordCompletedEntryIds,
                ).execute(
                    request = request,
                    options = SnsBatchExecutionOptions(maxInFlightBatches),
                )
            } catch (cause: SnsBatchTransportException) {
                counters.recordFailure()
                null
            }
        }
        blackhole.consume(result?.successful?.size ?: -1)
    }
}

/**
 * JMH JSON에 low-cardinality 동시성·정리·heap 보조 지표를 추가합니다.
 * `peakHeapBytes`는 invocation 동안 JVM memory-pool이 보고한 peak sample이며,
 * 외부 publisher가 보관하는 객체 그래프의 상한을 의미하지 않습니다.
 */
@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
open class SnsBatchBenchmarkCounters {

    private val active = AtomicInteger()
    private val maxActive = AtomicInteger()
    private val chunks = AtomicInteger()
    private val completedEntries = AtomicLong()
    private val completedEntryIds = AtomicLong()
    private val failures = AtomicInteger()
    private var activeAfter: Int = 0
    private var peakHeapBytes: Long = 0
    private val memoryPools: List<MemoryPoolMXBean> =
        ManagementFactory.getMemoryPoolMXBeans().filter { it.isValid }

    @Setup(Level.Invocation)
    fun reset() {
        active.set(0)
        maxActive.set(0)
        chunks.set(0)
        completedEntries.set(0)
        completedEntryIds.set(0)
        failures.set(0)
        activeAfter = 0
        peakHeapBytes = 0
        memoryPools.forEach { pool ->
            runCatching { pool.resetPeakUsage() }
        }
    }

    @TearDown(Level.Invocation)
    fun captureCleanup() {
        activeAfter = active.get()
        peakHeapBytes = memoryPools.asSequence()
            .mapNotNull { pool -> runCatching { pool.peakUsage?.used }.getOrNull() }
            .maxOrNull()
            ?: 0
    }

    fun recordStart(entryCount: Int) {
        val current = active.incrementAndGet()
        maxActive.accumulateAndGet(current, ::maxOf)
        chunks.incrementAndGet()
        require(entryCount in 1..10) { "SNS benchmark chunk size must be between 1 and 10." }
    }

    fun recordCompletion(entryCount: Int) {
        completedEntries.addAndGet(entryCount.toLong())
        active.decrementAndGet()
    }

    fun maxActive(): Int = maxActive.get()

    fun chunks(): Int = chunks.get()

    fun completedEntries(): Long = completedEntries.get()

    fun completedEntryIds(): Long = completedEntryIds.get()

    fun failures(): Int = failures.get()

    fun activeAfter(): Int = activeAfter

    fun peakHeapBytes(): Long = peakHeapBytes

    fun recordCompletedEntryIds(ids: List<String>) {
        completedEntryIds.addAndGet(ids.size.toLong())
    }

    fun recordFailure() {
        failures.incrementAndGet()
    }
}

private fun successResponseFor(entries: List<SnsPublishBatchEntry>): PublishBatchResponse =
    PublishBatchResponse.builder()
        .successful(
            entries.map { entry ->
                PublishBatchResultEntry.builder()
                    .id(entry.id)
                    .messageId("message-${entry.id}")
                    .build()
            },
        )
        .build()
