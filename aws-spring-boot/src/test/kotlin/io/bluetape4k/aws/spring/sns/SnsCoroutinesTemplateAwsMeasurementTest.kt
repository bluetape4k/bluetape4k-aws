package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry
import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import jdk.jfr.Recording

/** 승인된 계정의 AWS SNS batch 경로를 측정하고 민감한 입력 없이 증거를 남깁니다. */
class SnsCoroutinesTemplateAwsMeasurementTest {

    @Test
    @EnabledIfSystemProperty(named = "bluetape4k.aws.sns.real-aws-measurement", matches = "true")
    fun `measure actual AWS SNS batch publisher and write redacted artifacts`() = runSuspendIO {
        measureAwsBatchRows()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun measureAwsBatchRows() {
        val output = Path.of(requiredProperty("bluetape4k.aws.sns.measurement.output"))
        val regionName = requiredProperty("bluetape4k.aws.sns.measurement.region")
        val retentionSeconds = requiredProperty("bluetape4k.aws.sns.measurement.retention-seconds").toLong()
        require(retentionSeconds >= MIN_RETENTION_SECONDS) {
            "retention-seconds must be at least $MIN_RETENTION_SECONDS"
        }
        require(!System.getenv("AWS_PROFILE").isNullOrBlank()) {
            "AWS_PROFILE must select the approved credential source"
        }
        Files.createDirectories(output)

        val jfr = JfrCapture.start()
        var client: SnsAsyncClient? = null
        var topicArn: String? = null
        var primaryFailure: Throwable? = null
        var measurementCompleted = false
        var retentionCompleted = false
        fun recordFailure(cause: Throwable) {
            val existing = primaryFailure
            if (existing == null) {
                primaryFailure = cause
            } else {
                existing.addSuppressed(cause)
            }
        }

        try {
            try {
                val awsClient = SnsAsyncClient.builder()
                    .credentialsProvider(DefaultCredentialsProvider.builder().build())
                    .region(Region.of(regionName))
                    .build()
                client = awsClient
                topicArn = awsClient.createTopic {
                    it.name("measure-${Base58.randomString(16)}")
                }.await().topicArn()

                val rows = buildList {
                    for (scenario in SNS_AWS_MEASUREMENT_SCENARIOS) {
                        for (entryCount in SNS_AWS_MEASUREMENT_ENTRY_COUNTS) {
                            for (maxInFlight in SNS_AWS_MEASUREMENT_MAX_IN_FLIGHT) {
                                add(
                                    measureAwsRow(
                                        client = awsClient,
                                        topicArn = requireNotNull(topicArn),
                                        scenario = scenario,
                                        entryCount = entryCount,
                                        maxInFlight = maxInFlight,
                                    )
                                )
                            }
                        }
                    }
                }
                rows.forEach(::assertAwsRow)
                writeMeasurementArtifacts(output, rows, regionName, retentionSeconds, jfr)
                measurementCompleted = true
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                recordFailure(cause)
            }

            try {
                topicArn?.let { arn ->
                    client?.deleteTopic { it.topicArn(arn) }?.await()
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                recordFailure(cause)
            } finally {
                try {
                    client?.close()
                } catch (cause: Throwable) {
                    recordFailure(cause)
                }
            }

            if (primaryFailure == null) {
                delay(retentionSeconds * 1_000L)
                retentionCompleted = true
            }
        } finally {

            try {
                jfr.finish(Path.of(output.toString(), "heap-profile.jfr"))
            } catch (cause: Throwable) {
                recordFailure(cause)
            }
            writeAllocationSummary(output)
            writeRetentionArtifact(output, retentionSeconds, retentionCompleted)
            writeCapabilityArtifact(output, jfr, measurementCompleted, retentionCompleted)
        }
        primaryFailure?.let { throw it }
    }

    private suspend fun measureAwsRow(
        client: SnsAsyncClient,
        topicArn: String,
        scenario: String,
        entryCount: Int,
        maxInFlight: Int,
    ): AwsMeasurementRow {
        repeat(SNS_AWS_MEASUREMENT_WARMUPS) {
            executeAwsSample(client, topicArn, scenario, entryCount, maxInFlight)
        }
        val samples = buildList {
            repeat(SNS_AWS_MEASUREMENT_REPETITIONS) {
                add(executeAwsSample(client, topicArn, scenario, entryCount, maxInFlight))
            }
        }
        val durations = samples.map { it.durationNanos }
        val throughputs = samples.map { it.throughputMessagesPerSecond }
        return AwsMeasurementRow(
            scenario = scenario,
            entryCount = entryCount,
            maxInFlight = maxInFlight,
            throughputMessagesPerSecond = median(throughputs),
            p50Nanos = percentile(durations, 0.50),
            p95Nanos = percentile(durations, 0.95),
            p99Nanos = percentile(durations, 0.99),
            peakHeapBytes = samples.maxOf { it.peakHeapBytes },
            activeAfter = samples.maxOf { it.activeAfter },
            maxActive = samples.maxOf { it.maxActive },
            observedChunks = samples.maxOf { it.observedChunks },
            completedEntries = samples.maxOf { it.completedEntries },
            completedEntryIds = samples.maxOf { it.completedEntryIds },
            successfulEntries = samples.maxOf { it.successfulEntries },
            failedEntries = samples.maxOf { it.failedEntries },
        )
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    private suspend fun executeAwsSample(
        client: SnsAsyncClient,
        topicArn: String,
        scenario: String,
        entryCount: Int,
        maxInFlight: Int,
    ): AwsMeasurementSample {
        val entries = (1..entryCount).map { index ->
            SnsPublishBatchEntry(
                id = "entry-$index-${Base58.randomString(16)}",
                message = "payload-$index-${Base58.randomString(24)}",
            )
        }
        val telemetry = AwsMeasurementTelemetry()
        val memoryPools = memoryPools()
        memoryPools.forEach { pool ->
            if (pool.isValid) {
                pool.resetPeakUsage()
            }
        }
        val requestTopicArn = if (scenario == "success") topicArn else missingTopicArn(topicArn)
        val request = SnsPublishBatchRequest(requestTopicArn, entries)
        val startedAt = System.nanoTime()
        val result = try {
            SnsBatchExecutor(
                publishChunk = { _, chunk ->
                    val current = telemetry.active.incrementAndGet()
                    telemetry.maxActive.accumulateAndGet(current, ::maxOf)
                    telemetry.observedChunks.incrementAndGet()
                    try {
                        client.publishBatch(
                            PublishBatchRequest.builder()
                                .topicArn(requestTopicArn)
                                .publishBatchRequestEntries(chunk.map(::toSdkEntry))
                                .build()
                        ).await()
                    } finally {
                        telemetry.active.decrementAndGet()
                    }
                },
                onCompletedEntryIds = { ids ->
                    telemetry.completedEntries.addAndGet(ids.size)
                    telemetry.completedEntryIds.addAndGet(ids.size)
                },
            ).execute(request, SnsBatchExecutionOptions(maxInFlight))
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: SnsBatchTransportException) {
            if (scenario == "transport") {
                null
            } else {
                throw cause
            }
        }
        val durationNanos = System.nanoTime() - startedAt
        return AwsMeasurementSample(
            durationNanos = durationNanos,
            throughputMessagesPerSecond = entryCount * 1_000_000_000.0 / durationNanos,
            peakHeapBytes = peakHeapBytes(memoryPools),
            activeAfter = telemetry.active.get(),
            maxActive = telemetry.maxActive.get(),
            observedChunks = telemetry.observedChunks.get(),
            completedEntries = telemetry.completedEntries.get(),
            completedEntryIds = telemetry.completedEntryIds.get(),
            successfulEntries = result?.successful?.size ?: 0,
            failedEntries = result?.failed?.size ?: 0,
        )
    }

    private fun assertAwsRow(row: AwsMeasurementRow) {
        row.activeAfter shouldBeEqualTo 0
        row.maxActive shouldBeLessOrEqualTo row.maxInFlight
        row.observedChunks shouldBeLessOrEqualTo (row.entryCount + 9) / 10
        if (row.scenario == "success") {
            row.observedChunks shouldBeEqualTo (row.entryCount + 9) / 10
            row.completedEntries shouldBeEqualTo row.entryCount
            row.completedEntryIds shouldBeEqualTo row.entryCount
            row.successfulEntries shouldBeEqualTo row.entryCount
            row.failedEntries shouldBeEqualTo 0
        } else {
            row.completedEntries shouldBeLessOrEqualTo row.entryCount
            row.completedEntryIds shouldBeLessOrEqualTo row.entryCount
            row.successfulEntries shouldBeEqualTo 0
            row.failedEntries shouldBeEqualTo 0
        }
        (row.peakHeapBytes >= 0L).shouldBeTrue()
    }

    private fun writeMeasurementArtifacts(
        output: Path,
        rows: List<AwsMeasurementRow>,
        regionName: String,
        retentionSeconds: Long,
        jfr: JfrCapture,
    ) {
        writeText(
            output.resolve("throughput.json"),
            rows.joinToString(prefix = "[\n", postfix = "\n]\n") { row -> jmhRecord(row, "thrpt") },
        )
        writeText(
            output.resolve("latency.json"),
            rows.joinToString(prefix = "[\n", postfix = "\n]\n") { row -> jmhRecord(row, "avgt") },
        )
        writeText(
            output.resolve("environment.json"),
            """{
              "schema_version": 1,
              "backend": "aws",
              "credential_source": "AWS_PROFILE",
              "account_id_verified": false,
              "region": "${jsonEscape(regionName)}",
              "endpoint_override": false,
              "warmups": $SNS_AWS_MEASUREMENT_WARMUPS,
              "repetitions": $SNS_AWS_MEASUREMENT_REPETITIONS,
              "retention_seconds": $retentionSeconds,
              "jfr_events": ${jfr.enabledEvents.toJsonArray()},
              "jfr_unavailable_events": ${jfr.unavailableEvents.toJsonArray()},
              "matrix": {
                "entryCount": [1, 10, 11, 20, 21, 100],
                "maxInFlightBatches": [1, 2, 4],
                "scenario": ["success", "transport"]
              }
            }
            """.trimIndent() + "\n",
        )
    }

    private fun jmhRecord(row: AwsMeasurementRow, mode: String): String {
        val score = if (mode == "thrpt") {
            row.throughputMessagesPerSecond / row.entryCount
        } else {
            row.p50Nanos
        }
        val unit = if (mode == "thrpt") "ops/s" else "ns/op"
        val percentiles = if (mode == "avgt") {
            ",\"scorePercentiles\":{\"50.0\":${row.p50Nanos},\"95.0\":${row.p95Nanos},\"99.0\":${row.p99Nanos}}"
        } else {
            ""
        }
        return """  {
          "mode": "$mode",
          "params": {
            "entryCount": "${row.entryCount}",
            "maxInFlightBatches": "${row.maxInFlight}",
            "scenario": "${row.scenario}"
          },
          "primaryMetric": {"score": $score, "scoreUnit": "$unit"$percentiles},
          "secondaryMetrics": {
            "activeAfter": {"score": ${row.activeAfter}},
            "maxActive": {"score": ${row.maxActive}},
            "completedEntries": {"score": ${row.completedEntries}},
            "completedEntryIds": {"score": ${row.completedEntryIds}},
            "observedChunks": {"score": ${row.observedChunks}},
            "resultEntries": {"score": ${row.successfulEntries + row.failedEntries}},
            "peakHeapBytes": {"score": ${row.peakHeapBytes}}
          }
        }""".trimIndent()
    }

    private fun writeAllocationSummary(output: Path) {
        val histogram = try {
            val process = ProcessBuilder("jcmd", ProcessHandle.current().pid().toString(), "GC.class_histogram")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(30, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
            }
            val rows = CLASS_HISTOGRAM_PATTERN.findAll(text).take(100).map { match ->
                "{\"class\":\"${jsonEscape(match.groupValues[4].trim())}\"," +
                    "\"instances\":${match.groupValues[2]},\"bytes\":${match.groupValues[3]}}"
            }.toList()
            if (completed && process.exitValue() == 0 && rows.isNotEmpty()) {
                """{"schema_version":1,"status":"available","sampled_with":"jcmd GC.class_histogram","rows":[${rows.joinToString(",")}]}
"""
            } else {
                """{"schema_version":1,"status":"unavailable","sampled_with":"jcmd GC.class_histogram","rows":[]}
"""
            }
        } catch (_: Throwable) {
            """{"schema_version":1,"status":"unavailable","sampled_with":"jcmd GC.class_histogram","rows":[]}
"""
        }
        writeText(output.resolve("allocation-summary.json"), histogram)
    }

    private fun writeRetentionArtifact(output: Path, retentionSeconds: Long, completed: Boolean) {
        writeText(
            output.resolve("retention.json"),
            """{
              "schema_version": 1,
              "backend": "aws",
              "phase": "post_delete",
              "retention_seconds": $retentionSeconds,
              "status": "${if (completed) "completed" else "measurement_failed"}",
              "profile": "heap-profile.jfr",
              "allocation_summary": "allocation-summary.json"
            }
            """.trimIndent() + "\n",
        )
    }

    private fun writeCapabilityArtifact(
        output: Path,
        jfr: JfrCapture,
        measurementCompleted: Boolean,
        retentionCompleted: Boolean,
    ) {
        writeText(
            output.resolve("capability.json"),
            """{
              "schema_version": 1,
              "backend": "aws",
              "measurement_status": "${if (measurementCompleted) "completed" else "incomplete"}",
              "retention_completed": $retentionCompleted,
              "caller_cancellation": {
                "status": "not_deterministically_reproducible",
                "measured": false,
                "executor_tests": "SnsBatchExecutorTest"
              },
              "mixed_result": {
                "status": "backend_capability_not_reproducible",
                "measured": false,
                "executor_tests": "SnsBatchExecutorTest"
              },
              "protocol_mismatch": {
                "status": "backend_capability_not_reproducible",
                "measured": false,
                "executor_tests": "SnsBatchExecutorTest"
              },
              "jfr": {
                "status": "${if (jfr.enabledEvents.isNotEmpty()) "available" else "unavailable"}",
                "allocation_and_retention_events_only": true
              }
            }
            """.trimIndent() + "\n",
        )
    }

    private fun toSdkEntry(entry: SnsPublishBatchEntry): PublishBatchRequestEntry =
        PublishBatchRequestEntry.builder()
            .id(entry.id)
            .message(entry.message)
            .build()

    private fun missingTopicArn(topicArn: String): String =
        "${topicArn.substringBeforeLast(':')}:missing-${Base58.randomString(16)}"

    private fun memoryPools(): List<MemoryPoolMXBean> =
        ManagementFactory.getMemoryPoolMXBeans().filter { it.isValid }

    private fun peakHeapBytes(memoryPools: List<MemoryPoolMXBean>): Long = memoryPools.asSequence()
        .mapNotNull { pool ->
            try {
                pool.peakUsage?.used
            } catch (_: RuntimeException) {
                null
            }
        }
        .maxOrNull()
        ?: 0L

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun percentile(values: List<Long>, fraction: Double): Double {
        val sorted = values.sorted()
        val index = kotlin.math.ceil((sorted.size - 1) * fraction).toInt()
        return sorted[index].toDouble()
    }

    private fun requiredProperty(name: String): String =
        System.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: error("$name must be set by the approved measurement wrapper")

    private fun writeText(path: Path, content: String) {
        Files.writeString(path, content)
        ensureArtifact(path)
    }

    private fun ensureArtifact(path: Path) {
        // Wrapper checks parse JSON and keep this test free of a new runtime dependency.
        require(Files.size(path) > 0L) { "measurement artifact must not be empty: ${path.fileName}" }
    }

    private class AwsMeasurementTelemetry {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val observedChunks = AtomicInteger()
        val completedEntries = AtomicInteger()
        val completedEntryIds = AtomicInteger()
    }

    private data class AwsMeasurementSample(
        val durationNanos: Long,
        val throughputMessagesPerSecond: Double,
        val peakHeapBytes: Long,
        val activeAfter: Int,
        val maxActive: Int,
        val observedChunks: Int,
        val completedEntries: Int,
        val completedEntryIds: Int,
        val successfulEntries: Int,
        val failedEntries: Int,
    )

    private data class AwsMeasurementRow(
        val scenario: String,
        val entryCount: Int,
        val maxInFlight: Int,
        val throughputMessagesPerSecond: Double,
        val p50Nanos: Double,
        val p95Nanos: Double,
        val p99Nanos: Double,
        val peakHeapBytes: Long,
        val activeAfter: Int,
        val maxActive: Int,
        val observedChunks: Int,
        val completedEntries: Int,
        val completedEntryIds: Int,
        val successfulEntries: Int,
        val failedEntries: Int,
    )

    private class JfrCapture private constructor(
        private val recording: Recording,
        val enabledEvents: List<String>,
        val unavailableEvents: List<String>,
    ) {
        fun finish(path: Path) {
            recording.stop()
            recording.dump(path)
            recording.close()
        }

        companion object {
            fun start(): JfrCapture {
                val recording = Recording()
                recording.setSettings(emptyMap())
                val unavailable = mutableListOf<String>()
                val enabled = JFR_EVENTS.filter { event ->
                    try {
                        recording.enable(event)
                        true
                    } catch (_: IllegalArgumentException) {
                        unavailable += event
                        false
                    }
                }
                recording.start()
                return JfrCapture(recording, enabled, unavailable)
            }
        }
    }
}

private const val MIN_RETENTION_SECONDS: Long = 60
private const val SNS_AWS_MEASUREMENT_WARMUPS: Int = 1
private const val SNS_AWS_MEASUREMENT_REPETITIONS: Int = 3
private val SNS_AWS_MEASUREMENT_ENTRY_COUNTS = listOf(1, 10, 11, 20, 21, 100)
private val SNS_AWS_MEASUREMENT_MAX_IN_FLIGHT = listOf(1, 2, 4)
private val SNS_AWS_MEASUREMENT_SCENARIOS = listOf("success", "transport")
private val JFR_EVENTS = listOf(
    "jdk.ObjectAllocationInNewTLAB",
    "jdk.ObjectAllocationOutsideTLAB",
    "jdk.ObjectAllocationSample",
    "jdk.OldObjectSample",
    "jdk.GarbageCollection",
    "jdk.GCPhasePause",
    "jdk.ThreadAllocationStatistics",
)
private val CLASS_HISTOGRAM_PATTERN = "^\\s*(\\d+):\\s+(\\d+)\\s+(\\d+)\\s+(.+)$".toRegex()

private fun List<String>.toJsonArray(): String =
    if (isEmpty()) {
        "[]"
    } else {
        joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"") { jsonEscape(it) }
    }

private fun jsonEscape(value: String): String = buildString(value.length + 8) {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}
