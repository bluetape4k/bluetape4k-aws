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
        private const val WARMUP_ITERATIONS: Int = 3
        private const val MEASUREMENT_ITERATIONS: Int = 10
        private const val DISTINCT_FAILURE_VOLUME: Int = 20
        private const val MAX_RETAINED_SUPPRESSED_FAILURES: Int = 16
    }
}
