package io.bluetape4k.aws.bedrock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Bedrock callback 경계를 같은 JVM·dispatcher에서 반복하는 controlled regression harness입니다.
 * 절대 benchmark나 외부 publisher latency 보장을 제공하지 않으며, 실제 지연·heap/throughput은 #506에서 측정합니다.
 */
class BedrockRuntimePerformanceTest {

    @Test
    fun controlledPublisherRecordsNormalCancellationFailureReplacementPaths() = runSuspendIO {
        val adapter = BedrockRuntimePerformanceRuntimeAdapter()
        val scenarios = BedrockRuntimePerformanceRuntimeAdapter.Scenario.entries
        repeat(WARMUP_ITERATIONS) {
            scenarios.forEach { adapter.run(it) }
        }
        val samples = buildList {
            repeat(MEASUREMENT_ITERATIONS) {
                scenarios.forEach { add(adapter.run(it)) }
            }
        }

        samples.map { it.scenario }.toSet() shouldBeEqualTo scenarios.toSet()
        samples.forEach { sample ->
            sample.pendingCallbackCount shouldBeEqualTo 0
            (sample.publisherCancelCount >= 1).shouldBeTrue()
        }
        writeArtifacts(samples)
    }

    @Test
    fun boundedFailureRunKeepsPrimarySamplesMarkerAndPendingMapBounded() = runSuspendIO {
        val sample = BedrockRuntimePerformanceRuntimeAdapter().run(
            scenario = BedrockRuntimePerformanceRuntimeAdapter.Scenario.OPERATION_FAILURE,
            failureVolume = DISTINCT_FAILURE_VOLUME,
        )

        sample.operationFailureIsPrimary.shouldBeTrue()
        sample.retainedSuppressedCount shouldBeEqualTo MAX_RETAINED_SUPPRESSED_FAILURES
        sample.overflowMarkerCount shouldBeEqualTo 1
        sample.overflowDroppedCount shouldBeEqualTo
            (DISTINCT_FAILURE_VOLUME - MAX_RETAINED_SUPPRESSED_FAILURES).toLong()
        sample.markerRetainsOriginalThrowable.shouldBeFalse()
        sample.duplicateIdentityCount shouldBeEqualTo 1
        sample.pendingCallbackCount shouldBeEqualTo 0
        writeRetentionArtifact(sample)
    }

    @Test
    fun externalPublisherCleanupModesSeparatePublisherAndCoordinatorLatency() = runSuspendIO {
        val adapter = BedrockRuntimePerformanceRuntimeAdapter()
        val scenarios = BedrockRuntimePerformanceRuntimeAdapter.Scenario.entries
        val modes = BedrockRuntimePerformanceRuntimeAdapter.CleanupMode.entries
        val samples = buildList {
            repeat(LATENCY_MEASUREMENT_ITERATIONS) {
                scenarios.forEach { scenario ->
                    modes.forEach { mode ->
                        add(adapter.run(scenario, mode))
                    }
                }
            }
        }

        samples.map { it.scenario }.toSet() shouldBeEqualTo scenarios.toSet()
        samples.map { it.cleanupMode }.toSet() shouldBeEqualTo modes.toSet()
        samples.forEach { sample ->
            (sample.coordinatorCleanupNanos > 0L).shouldBeTrue()
            (sample.publisherCleanupNanos >= 0L).shouldBeTrue()
            sample.pendingCallbackCount shouldBeEqualTo 0
            if (sample.cleanupMode == BedrockRuntimePerformanceRuntimeAdapter.CleanupMode.BLOCKING) {
                (sample.watchdogReleaseCount > 0).shouldBeTrue()
                (sample.blockingWaitNanos > 0L).shouldBeTrue()
            } else {
                sample.watchdogReleaseCount shouldBeEqualTo 0
                sample.blockingWaitNanos shouldBeEqualTo 0L
            }
        }
        writeLatencyArtifacts(samples)
    }

    @Test
    fun longRunRecordsThroughputAllocationHeapAndRetention() = runSuspendIO {
        val result = BedrockRuntimePerformanceRuntimeAdapter().runLongRun(
            eventCount = LONG_RUN_EVENT_COUNT,
            measurementIterations = LONG_RUN_MEASUREMENT_ITERATIONS,
        )

        result.samples.size shouldBeEqualTo LONG_RUN_MEASUREMENT_ITERATIONS + 1
        result.samples.all { it.pendingCallbackCount == 0 }.shouldBeTrue()
        result.samples.all { it.eventCount == LONG_RUN_EVENT_COUNT }.shouldBeTrue()
        (result.throughputEventsPerSecond > 0.0).shouldBeTrue()
        (result.allocatedBytes > 0L).shouldBeTrue()
        result.operationFailureIsPrimary.shouldBeTrue()
        result.retainedSuppressedCount shouldBeEqualTo MAX_RETAINED_SUPPRESSED_FAILURES
        result.overflowMarkerCount shouldBeEqualTo 1
        result.overflowDroppedCount shouldBeEqualTo
            (DISTINCT_FAILURE_VOLUME - MAX_RETAINED_SUPPRESSED_FAILURES).toLong()
        result.markerRetainsOriginalThrowable.shouldBeFalse()
        writeLongRunArtifacts(result)
    }

    private fun writeArtifacts(samples: List<BedrockRuntimePerformanceRuntimeAdapter.Sample>) {
        val directory = evidenceDirectory()
        Files.createDirectories(directory)
        val candidateCommit = gitHead()
        val harnessHash = sha256(PERFORMANCE_HARNESS_SOURCE)
        val adapterHash = sha256(PERFORMANCE_ADAPTER_SOURCE)
        val metadata = "\"measurementKind\":\"controlled-regression\"," +
            "\"harnessSha256\":\"${harnessHash}\",\"adapterSha256\":\"${adapterHash}\"," +
            "\"jvm\":\"${System.getProperty("java.version")}\"," +
            "\"dispatcher\":\"Executors.newSingleThreadExecutor\",\"parallelism\":1," +
            "\"warmup\":${WARMUP_ITERATIONS},\"measurement\":${MEASUREMENT_ITERATIONS}"
        val baselineCommitFile = directory.resolve("baseline-commit.txt")
        val baselineCommit = if (Files.exists(baselineCommitFile)) {
            Files.readString(baselineCommitFile).trim()
        } else {
            candidateCommit.also { Files.writeString(baselineCommitFile, "${it}\n") }
        }
        val baselineMetadata = directory.resolve("baseline-commit.json")
        val baselineIsStale = !Files.exists(baselineMetadata) ||
            !Files.readString(baselineMetadata).contains("\"harnessSha256\":\"${harnessHash}\"") ||
            !Files.readString(baselineMetadata).contains("\"adapterSha256\":\"${adapterHash}\"")
        if (baselineIsStale) {
            Files.writeString(
                baselineMetadata,
                "{${metadata},\"commit\":\"${baselineCommit}\",\"runtimePath\":\"controlled\"}\n",
            )
            Files.writeString(directory.resolve("baseline-raw-samples.json"), samplesJson(samples))
            Files.writeString(directory.resolve("baseline-summary.json"), summaryJson(samples))
        }
        Files.writeString(
            directory.resolve("candidate-HEAD.json"),
            "{${metadata},\"commit\":\"${candidateCommit}\",\"baselineCommit\":\"${baselineCommit}\"," +
                "\"runtimePath\":\"controlled\"}\n",
        )
        Files.writeString(directory.resolve("candidate-raw-samples.json"), samplesJson(samples))
        Files.writeString(directory.resolve("candidate-summary.json"), summaryJson(samples))
        scenarios().forEach { scenario ->
            val scenarioSamples = samples.filter { it.scenario == scenario }
            val summary = "{\"scenario\":\"${scenario.name}\",\"p50Nanos\":" +
                "${percentile(scenarioSamples.map { it.coordinatorCleanupNanos }, 0.50)}," +
                "\"p95Nanos\":${percentile(scenarioSamples.map { it.coordinatorCleanupNanos }, 0.95)}," +
                "\"p99Nanos\":${percentile(scenarioSamples.map { it.coordinatorCleanupNanos }, 0.99)}}\n"
            Files.writeString(directory.resolve("candidate-${scenario}.json"), summary)
        }
    }

    private fun writeRetentionArtifact(sample: BedrockRuntimePerformanceRuntimeAdapter.Sample) {
        val directory = evidenceDirectory()
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("retention-summary.json"),
            "{\"failureVolume\":${sample.failureVolume}," +
                "\"operationFailureIsPrimary\":${sample.operationFailureIsPrimary}," +
                "\"retainedSuppressedCount\":${sample.retainedSuppressedCount}," +
                "\"overflowMarkerCount\":${sample.overflowMarkerCount}," +
                "\"overflowDroppedCount\":${sample.overflowDroppedCount}," +
                "\"duplicateIdentityCount\":${sample.duplicateIdentityCount}," +
                "\"markerRetainsOriginalThrowable\":${sample.markerRetainsOriginalThrowable}," +
                "\"pendingCallbackCount\":${sample.pendingCallbackCount}}\n",
        )
    }

    private fun writeLatencyArtifacts(
        samples: List<BedrockRuntimePerformanceRuntimeAdapter.Sample>,
    ) {
        val directory = issue506EvidenceDirectory()
        Files.createDirectories(directory)
        val metadata = issue506Metadata(
            measurementKind = "external-publisher-cleanup-latency",
            warmup = 0,
            measurement = LATENCY_MEASUREMENT_ITERATIONS,
        )
        val candidateCommit = gitHead()
        val baselineCommitFile = directory.resolve("baseline-commit.txt")
        val baselineCommit = if (Files.exists(baselineCommitFile)) {
            Files.readString(baselineCommitFile).trim()
        } else {
            candidateCommit.also { Files.writeString(baselineCommitFile, "$it\n") }
        }
        val baselineMetadata = directory.resolve("baseline-commit.json")
        val harnessHash = sourceHash(PERFORMANCE_HARNESS_SOURCE)
        val adapterHash = sourceHash(PERFORMANCE_ADAPTER_SOURCE)
        val publisherHash = sourceHash(PUBLISHER_SOURCE)
        val baselineIsStale = !Files.exists(baselineMetadata) ||
            !Files.readString(baselineMetadata).contains("\"harnessSha256\":\"${harnessHash}\"") ||
            !Files.readString(baselineMetadata).contains("\"adapterSha256\":\"${adapterHash}\"") ||
            !Files.readString(baselineMetadata).contains("\"publisherSha256\":\"${publisherHash}\"")
        if (baselineIsStale) {
            Files.writeString(
                baselineMetadata,
                "{${metadata},\"commit\":\"$baselineCommit\",\"runtimePath\":\"controlled\"}\n",
            )
            Files.writeString(directory.resolve("baseline-raw-samples.json"), latencySamplesJson(samples))
            Files.writeString(directory.resolve("baseline-summary.json"), latencySummaryJson(samples))
        }
        Files.writeString(
            directory.resolve("candidate-HEAD.json"),
            "{${metadata},\"commit\":\"$candidateCommit\",\"baselineCommit\":\"$baselineCommit\"," +
                "\"runtimePath\":\"controlled\"}\n",
        )
        Files.writeString(directory.resolve("candidate-raw-samples.json"), latencySamplesJson(samples))
        Files.writeString(directory.resolve("candidate-summary.json"), latencySummaryJson(samples))
        Files.writeString(directory.resolve("latency-raw-samples.json"), latencySamplesJson(samples))
        Files.writeString(directory.resolve("latency-summary.json"), latencySummaryJson(samples))
        Files.writeString(directory.resolve("latency-mode-summary.json"), latencyModeSummaryJson(samples))
    }

    private fun writeLongRunArtifacts(result: BedrockRuntimePerformanceRuntimeAdapter.LongRunResult) {
        val directory = issue506EvidenceDirectory()
        Files.createDirectories(directory)
        val metadata = issue506Metadata(
            measurementKind = "long-run-heap-throughput-retention",
            warmup = 1,
            measurement = result.measurementIterations,
        )
        val candidateCommit = gitHead()
        Files.writeString(
            directory.resolve("long-run-HEAD.json"),
            "{${metadata},\"commit\":\"$candidateCommit\",\"eventCount\":${result.eventCount}," +
                "\"runtimePath\":\"controlled\"}\n",
        )
        Files.writeString(
            directory.resolve("long-run-raw-samples.json"),
            latencySamplesJson(result.samples),
        )
        Files.writeString(
            directory.resolve("long-run-summary.json"),
            "{\"eventCount\":${result.eventCount},\"measurementIterations\":${result.measurementIterations}," +
                "\"samples\":${result.samples.size},\"pendingCallbackCount\":${result.pendingCallbackCount}," +
                "\"throughputEventsPerSecond\":${result.throughputEventsPerSecond}}\n",
        )
        Files.writeString(
            directory.resolve("throughput-summary.json"),
            "{\"events\":${result.eventCount * result.measurementIterations}," +
                "\"throughputEventsPerSecond\":${result.throughputEventsPerSecond}," +
                "\"p50Nanos\":${percentile(result.samples.map { it.coordinatorCleanupNanos }, 0.50)}," +
                "\"p95Nanos\":${percentile(result.samples.map { it.coordinatorCleanupNanos }, 0.95)}," +
                "\"p99Nanos\":${percentile(result.samples.map { it.coordinatorCleanupNanos }, 0.99)}}\n",
        )
        Files.writeString(
            directory.resolve("allocation-summary.json"),
            "{\"allocatedBytes\":${result.allocatedBytes}}\n",
        )
        Files.writeString(
            directory.resolve("heap-summary.json"),
            "{\"heapUsedBefore\":${result.heapUsedBefore},\"heapUsedAfter\":${result.heapUsedAfter}," +
                "\"heapDeltaBytes\":${result.heapDeltaBytes}}\n",
        )
        Files.writeString(
            directory.resolve("retention-summary.json"),
            "{\"operationFailureIsPrimary\":${result.operationFailureIsPrimary}," +
                "\"retainedSuppressedCount\":${result.retainedSuppressedCount}," +
                "\"overflowMarkerCount\":${result.overflowMarkerCount}," +
                "\"overflowDroppedCount\":${result.overflowDroppedCount}," +
                "\"markerRetainsOriginalThrowable\":${result.markerRetainsOriginalThrowable}," +
                "\"pendingCallbackCount\":${result.pendingCallbackCount}}\n",
        )
    }

    private fun issue506Metadata(
        measurementKind: String,
        warmup: Int,
        measurement: Int,
    ): String {
        val harnessHash = sourceHash(PERFORMANCE_HARNESS_SOURCE)
        val adapterHash = sourceHash(PERFORMANCE_ADAPTER_SOURCE)
        val publisherHash = sourceHash(PUBLISHER_SOURCE)
        return "\"measurementKind\":\"$measurementKind\"," +
            "\"harnessSha256\":\"$harnessHash\",\"adapterSha256\":\"$adapterHash\"," +
            "\"publisherSha256\":\"$publisherHash\",\"jvm\":\"${System.getProperty("java.version")}\"," +
            "\"dispatcher\":\"Executors.newSingleThreadExecutor\",\"parallelism\":1," +
            "\"warmup\":$warmup,\"measurement\":$measurement," +
            "\"command\":\"./gradlew --no-daemon --max-workers=1 --no-parallel " +
            "--no-build-cache :bluetape4k-aws-java:test --tests " +
            "io.bluetape4k.aws.bedrock.BedrockRuntimePerformanceTest\""
    }

    private fun latencySamplesJson(
        samples: List<BedrockRuntimePerformanceRuntimeAdapter.Sample>,
    ): String = samples.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { sample ->
        "{\"scenario\":\"${sample.scenario.name}\",\"cleanupMode\":\"${sample.cleanupMode.name}\"," +
            "\"eventCount\":${sample.eventCount},\"failureVolume\":${sample.failureVolume}," +
            "\"coordinatorCleanupNanos\":${sample.coordinatorCleanupNanos}," +
            "\"publisherCleanupNanos\":${sample.publisherCleanupNanos}," +
            "\"publisherCancelCount\":${sample.publisherCancelCount}," +
            "\"watchdogReleaseCount\":${sample.watchdogReleaseCount}," +
            "\"blockingWaitNanos\":${sample.blockingWaitNanos}," +
            "\"allocatedBytes\":${sample.allocatedBytes},\"heapUsedBefore\":${sample.heapUsedBefore}," +
            "\"heapUsedAfter\":${sample.heapUsedAfter},\"heapDeltaBytes\":${sample.heapDeltaBytes}," +
            "\"throughputEventsPerSecond\":${sample.throughputEventsPerSecond}," +
            "\"pendingCallbackCount\":${sample.pendingCallbackCount}}"
    }

    private fun latencySummaryJson(
        samples: List<BedrockRuntimePerformanceRuntimeAdapter.Sample>,
    ): String {
        val coordinator = samples.map { it.coordinatorCleanupNanos }
        val publisher = samples.map { it.publisherCleanupNanos }
        return "{\"samples\":${samples.size},\"coordinatorCleanupNanos\":{\"p50\":${percentile(coordinator, 0.50)}," +
            "\"p95\":${percentile(coordinator, 0.95)},\"p99\":${percentile(coordinator, 0.99)}}," +
            "\"publisherCleanupNanos\":{\"p50\":${percentile(publisher, 0.50)}," +
            "\"p95\":${percentile(publisher, 0.95)},\"p99\":${percentile(publisher, 0.99)}}}\n"
    }

    private fun latencyModeSummaryJson(
        samples: List<BedrockRuntimePerformanceRuntimeAdapter.Sample>,
    ): String = BedrockRuntimePerformanceRuntimeAdapter.CleanupMode.entries.joinToString(
        prefix = "{",
        postfix = "}\n",
        separator = ",",
    ) { mode ->
        val modeSamples = samples.filter { it.cleanupMode == mode }
        "\"${mode.name}\":${latencySummaryJson(modeSamples).trim()}"
    }

    private fun samplesJson(samples: List<BedrockRuntimePerformanceRuntimeAdapter.Sample>): String =
        samples.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { sample ->
            "{\"scenario\":\"${sample.scenario.name}\",\"eventCount\":${sample.eventCount}," +
                "\"failureVolume\":${sample.failureVolume}," +
                "\"coordinatorCleanupNanos\":${sample.coordinatorCleanupNanos}," +
                "\"publisherCleanupNanos\":${sample.publisherCleanupNanos}," +
                "\"publisherCancelCount\":${sample.publisherCancelCount}," +
                "\"pendingCallbackCount\":${sample.pendingCallbackCount}}"
        }

    private fun summaryJson(samples: List<BedrockRuntimePerformanceRuntimeAdapter.Sample>): String =
        "{\"samples\":${samples.size},\"p50Nanos\":" +
            "${percentile(samples.map { it.coordinatorCleanupNanos }, 0.50)}," +
            "\"p95Nanos\":${percentile(samples.map { it.coordinatorCleanupNanos }, 0.95)}," +
            "\"p99Nanos\":${percentile(samples.map { it.coordinatorCleanupNanos }, 0.99)}}\n"

    private fun percentile(values: List<Long>, fraction: Double): Long {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val index = ceil(sorted.size * fraction).toInt().coerceAtLeast(1) - 1
        return sorted[index.coerceAtMost(sorted.lastIndex)]
    }

    private fun scenarios() = BedrockRuntimePerformanceRuntimeAdapter.Scenario.entries

    private fun evidenceDirectory(): Path = repositoryRoot().resolve(".bluetape/evidence/issue-505/perf")

    private fun gitHead(): String = ProcessBuilder("git", "rev-parse", "HEAD")
        .redirectErrorStream(true)
        .start()
        .run {
            val value = inputStream.bufferedReader().readText().trim()
            waitFor()
            require(exitValue() == 0 && value.matches(Regex("[0-9a-f]{40}")))
            value
        }

    private fun sha256(relativePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(Files.readAllBytes(repositoryRoot().resolve(relativePath)))
            .joinToString("") { "%02x".format(it) }
    }

    private fun sourceHash(relativePath: String): String = sha256(relativePath)

    private fun repositoryRoot(): Path {
        var current = Path.of(".").toAbsolutePath().normalize()
        repeat(6) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("repository root with settings.gradle.kts was not found")
    }

    companion object {
        private const val PERFORMANCE_HARNESS_SOURCE =
            "aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimePerformanceTest.kt"
        private const val PERFORMANCE_ADAPTER_SOURCE =
            "aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimePerformanceRuntimeAdapter.kt"
        private const val PUBLISHER_SOURCE =
            "aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/RecordingSdkPublisher.kt"
        private const val WARMUP_ITERATIONS: Int = 3
        private const val MEASUREMENT_ITERATIONS: Int = 10
        private const val LATENCY_MEASUREMENT_ITERATIONS: Int = 2
        private const val LONG_RUN_EVENT_COUNT: Int = 256
        private const val LONG_RUN_MEASUREMENT_ITERATIONS: Int = 4
        private const val DISTINCT_FAILURE_VOLUME: Int = 20
        private const val MAX_RETAINED_SUPPRESSED_FAILURES: Int = 16
    }

    private fun issue506EvidenceDirectory(): Path = repositoryRoot().resolve(".bluetape/evidence/issue-506/perf")
}
