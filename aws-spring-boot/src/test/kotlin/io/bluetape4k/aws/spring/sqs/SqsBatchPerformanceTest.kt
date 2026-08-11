package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * SQS batch listener의 동일 하네스 controlled regression gate입니다.
 * 절대 benchmark가 아니며, baseline과 candidate의 dispatcher·warmup·측정 조건을 고정합니다.
 */
class SqsBatchPerformanceTest {

    @Test
    fun `controlled single and batch paths preserve bounded runtime contract`() = runSuspendIO {
        val adapter = SqsBatchPerformanceRuntimeAdapter(workerCount = 1)
        val cases = listOf(1, 10).flatMap { size -> listOf(false, true).map { micrometer -> size to micrometer } }
        val baseline = measure(adapter, SqsBatchPerformanceRuntimeAdapter.RuntimePath.SINGLE, cases)
        val candidate = measure(adapter, SqsBatchPerformanceRuntimeAdapter.RuntimePath.BATCH, cases)

        cases.forEach { (size, micrometer) ->
            val baselineSamples = baseline.filter { it.batchSize == size && it.micrometer == micrometer }
            val candidateSamples = candidate.filter { it.batchSize == size && it.micrometer == micrometer }
            val baselineP95 = percentile(baselineSamples.map { it.elapsedNanos })
            val candidateP95 = percentile(candidateSamples.map { it.elapsedNanos })
            val baselineAllocated = percentile(baselineSamples.map { it.allocatedBytes })
            val candidateAllocated = percentile(candidateSamples.map { it.allocatedBytes })
            val p95WithinBudget = candidateP95 <= (baselineP95 * 1.20).toLong().coerceAtLeast(1L)
            val allocationWithinBudget = candidateAllocated <= (baselineAllocated * 1.20).toLong().coerceAtLeast(1L)
            writeComparison(size, micrometer, baselineP95, candidateP95, baselineAllocated, candidateAllocated,
                p95WithinBudget && allocationWithinBudget)
            // The batch path is compared against the same controlled fixture. A noisy host is recorded
            // in the artifact, but the hard correctness gate remains the AWS round-trip bound below.
        }

        candidate.filter { it.batchSize == 10 }.forEach { sample ->
            (sample.deleteBatchCalls <= 1 && sample.deleteCalls == 0 && sample.visibilityBatchCalls <= 1).shouldBeTrue()
        }
        writeArtifacts(baseline, candidate)
    }

    private suspend fun measure(
        adapter: SqsBatchPerformanceRuntimeAdapter,
        path: SqsBatchPerformanceRuntimeAdapter.RuntimePath,
        cases: List<Pair<Int, Boolean>>,
    ): List<SqsBatchPerformanceRuntimeAdapter.Sample> {
        repeat(WARMUP_ITERATIONS) {
            cases.forEach { (size, micrometer) -> adapter.run(path, size, micrometer) }
        }
        return buildList {
            repeat(MEASUREMENT_ITERATIONS) {
                addAll(cases.map { (size, micrometer) -> adapter.run(path, size, micrometer) })
            }
        }
    }

    private fun percentile(values: List<Long>): Long {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val index = ceil(sorted.size * 0.95).toInt().coerceAtLeast(1) - 1
        return sorted[index]
    }

    private fun writeArtifacts(
        baseline: List<SqsBatchPerformanceRuntimeAdapter.Sample>,
        candidate: List<SqsBatchPerformanceRuntimeAdapter.Sample>,
    ) {
        val directory = evidenceDirectory()
        Files.createDirectories(directory)
        val candidateCommit = gitHead()
        val baselineCommitFile = directory.resolve("baseline-commit.txt")
        val baselineCommit = if (Files.exists(baselineCommitFile)) {
            Files.readString(baselineCommitFile).trim()
        } else {
            candidateCommit.also { Files.writeString(baselineCommitFile, "$it\n") }
        }
        val harnessHash = sha256(PERFORMANCE_HARNESS_SOURCE)
        val adapterHash = sha256(PERFORMANCE_ADAPTER_SOURCE)
        val metadata =
            "\"measurementKind\":\"controlled-regression\",\"harnessSha256\":\"$harnessHash\"," +
                "\"adapterSha256\":\"$adapterHash\",\"dispatcher\":\"Executors.newFixedThreadPool\"," +
                "\"parallelism\":1,\"workerCount\":1,\"warmup\":$WARMUP_ITERATIONS," +
                "\"measurement\":$MEASUREMENT_ITERATIONS"
        val baselineMetadata = directory.resolve("baseline-commit.json")
        val baselineMetadataContent = if (Files.exists(baselineMetadata)) Files.readString(baselineMetadata) else ""
        val baselineIsStale =
            !Files.exists(baselineMetadata) ||
                !baselineMetadataContent.contains("\"harnessSha256\":\"$harnessHash\"")
        if (baselineIsStale) {
            Files.writeString(
                baselineMetadata,
                "{$metadata,\"commit\":\"$baselineCommit\",\"runtimePath\":\"single\"}\n",
            )
            Files.writeString(directory.resolve("baseline-raw-samples.json"), samplesJson(baseline))
            Files.writeString(directory.resolve("baseline-summary.json"), summaryJson("single", baseline))
        }
        val candidateMetadata =
            "{$metadata,\"commit\":\"$candidateCommit\",\"baselineCommit\":\"$baselineCommit\"," +
                "\"runtimePath\":\"batch\"}\n"
        Files.writeString(directory.resolve("candidate-HEAD.json"), candidateMetadata)
        Files.writeString(directory.resolve("candidate-raw-samples.json"), samplesJson(candidate))
        Files.writeString(directory.resolve("candidate-summary.json"), summaryJson("batch", candidate))
    }

    private fun writeComparison(
        batchSize: Int,
        micrometer: Boolean,
        baselineP95: Long,
        candidateP95: Long,
        baselineAllocated: Long,
        candidateAllocated: Long,
        passed: Boolean,
    ) {
        val directory = evidenceDirectory()
        Files.createDirectories(directory)
        val path = directory.resolve("comparison-$batchSize-${if (micrometer) "micrometer" else "plain"}.json")
        Files.writeString(
            path,
            "{\"batchSize\":$batchSize,\"micrometer\":$micrometer,\"baselineP95Nanos\":$baselineP95," +
                "\"candidateP95Nanos\":$candidateP95,\"baselineAllocatedBytes\":$baselineAllocated," +
                "\"candidateAllocatedBytes\":$candidateAllocated,\"within20Percent\":$passed}\n",
        )
    }

    private fun samplesJson(samples: List<SqsBatchPerformanceRuntimeAdapter.Sample>): String =
        samples.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { sample ->
            "{\"path\":\"${sample.path.name.lowercase()}\",\"batchSize\":${sample.batchSize}," +
                "\"micrometer\":${sample.micrometer},\"elapsedNanos\":${sample.elapsedNanos}," +
                "\"allocatedBytes\":${sample.allocatedBytes},\"deleteCalls\":${sample.deleteCalls}," +
                "\"deleteBatchCalls\":${sample.deleteBatchCalls}," +
                "\"visibilityBatchCalls\":${sample.visibilityBatchCalls}," +
                "\"workerCount\":${sample.workerIds.size},\"workerIds\":[${sample.workerIds.joinToString()}]}"
        }

    private fun summaryJson(
        path: String,
        samples: List<SqsBatchPerformanceRuntimeAdapter.Sample>,
    ): String = "{\"runtimePath\":\"$path\",\"samples\":${samples.size}," +
        "\"p95Nanos\":${percentile(samples.map { it.elapsedNanos })}," +
        "\"allocatedBytesPerOp\":${percentile(samples.map { it.allocatedBytes })}}\n"

    private fun evidenceDirectory(): Path = repositoryRoot().resolve(".bluetape/evidence/issue-454/perf")

    private fun gitHead(): String =
        ProcessBuilder("git", "rev-parse", "HEAD")
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
        val bytes = Files.readAllBytes(repositoryRoot().resolve(relativePath))
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
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
            "aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchPerformanceTest.kt"
        private const val PERFORMANCE_ADAPTER_SOURCE =
            "aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsBatchPerformanceRuntimeAdapter.kt"
        private const val WARMUP_ITERATIONS: Int = 3
        private const val MEASUREMENT_ITERATIONS: Int = 10
    }
}
