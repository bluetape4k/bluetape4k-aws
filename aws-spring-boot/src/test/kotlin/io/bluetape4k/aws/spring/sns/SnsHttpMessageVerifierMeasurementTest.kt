package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpFullResponse
import software.amazon.awssdk.messagemanager.sns.SnsMessageManager
import software.amazon.awssdk.regions.Region
import java.io.ByteArrayInputStream
import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * 외부 AWS 없이 SNS 서명 검증 경계의 대표 throughput과 heap 측정값을 수집합니다.
 *
 * 이 테스트는 절대 성능 기준선이 아닙니다. 동일 JVM에서 certificate cache hit/miss를
 * 고정된 warmup·반복 수로 실행하고 raw 결과를 JSON으로 남겨 호스트별 차이를 보존합니다.
 */
class SnsHttpMessageVerifierMeasurementTest {

    @Test
    @EnabledIfSystemProperty(named = MEASUREMENT_PROPERTY, matches = "true")
    fun `measure local signature verification cache hit and miss`() {
        val output = Path.of(requiredProperty(OUTPUT_PROPERTY))
        val rows = listOf(
            measureScenario("cache-hit") { notificationV2 },
            measureScenario("cache-miss") { index ->
                notificationV2.replace(BASE_CERTIFICATE_URL, certificateUrl(index))
            },
        )

        rows.single { it.scenario == "cache-hit" }.certificateFetches shouldBeEqualTo 1
        (rows.single { it.scenario == "cache-miss" }.certificateFetches > 1).shouldBeTrue()
        rows.all { row ->
            row.samples.all { sample ->
                sample.elapsedNanos > 0L && sample.throughputOpsPerSecond > 0.0
            }
        }
            .shouldBeTrue()
        rows.all { row -> row.samples.all { it.peakHeapBytes >= 0L } }.shouldBeTrue()

        Files.createDirectories(output.parent ?: Path.of("."))
        Files.writeString(output, reportJson(rows), StandardCharsets.UTF_8)
        require(Files.size(output) > 0L) { "measurement artifact must not be empty: ${output.fileName}" }
    }

    private fun measureScenario(
        scenario: String,
        message: (Int) -> String,
    ): MeasurementRow {
        val client = MeasurementHttpClient(signingCertificate)
        val verifier = SnsHttpMessageVerifier(
            SnsMessageManager.builder()
                .httpClient(client)
                .region(Region.US_WEST_2)
                .build(),
        )
        val pools = memoryPools()

        return try {
            repeat(WARMUP_OPERATIONS) { index ->
                verifier.verify(
                    message(index),
                    messageTypeHeader = "Notification",
                    expectedTopicArn = TOPIC_ARN,
                )
            }
            val samples = buildList {
                repeat(MEASUREMENT_SAMPLES) { sampleIndex ->
                    pools.forEach { pool -> runCatching { pool.resetPeakUsage() } }
                    val startedAt = System.nanoTime()
                    repeat(MEASUREMENT_OPERATIONS) { index ->
                        verifier.verify(
                            message(WARMUP_OPERATIONS + sampleIndex * MEASUREMENT_OPERATIONS + index),
                            messageTypeHeader = "Notification",
                            expectedTopicArn = TOPIC_ARN,
                        )
                    }
                    val elapsedNanos = (System.nanoTime() - startedAt).coerceAtLeast(1L)
                    add(
                        MeasurementSample(
                            elapsedNanos = elapsedNanos,
                            throughputOpsPerSecond =
                                MEASUREMENT_OPERATIONS * 1_000_000_000.0 / elapsedNanos,
                            peakHeapBytes = peakHeapBytes(pools),
                        ),
                    )
                }
            }
            MeasurementRow(scenario, client.requestCount, samples)
        } finally {
            verifier.close()
        }
    }

    private fun reportJson(rows: List<MeasurementRow>): String = buildString {
        appendLine("{")
        appendLine("  \"schema_version\": 1,")
        appendLine("  \"backend\": \"local-sdk-double\",")
        appendLine("  \"fixture\": \"notification-v2.json\",")
        appendLine("  \"warmup_operations\": $WARMUP_OPERATIONS,")
        appendLine("  \"measurement_samples\": $MEASUREMENT_SAMPLES,")
        appendLine("  \"operations_per_sample\": $MEASUREMENT_OPERATIONS,")
        appendLine("  \"scenarios\": [")
        rows.forEachIndexed { rowIndex, row ->
            appendLine("    {")
            appendLine("      \"name\": \"${row.scenario}\",")
            appendLine("      \"certificate_fetches\": ${row.certificateFetches},")
            appendLine("      \"p95_elapsed_nanos\": ${percentile(row.samples.map { it.elapsedNanos })},")
            appendLine("      \"samples\": [")
            row.samples.forEachIndexed { sampleIndex, sample ->
                append("        {\"elapsed_nanos\": ${sample.elapsedNanos},")
                append(" \"throughput_ops_per_second\": ${sample.throughputOpsPerSecond},")
                append(" \"peak_heap_bytes\": ${sample.peakHeapBytes}}")
                appendLine(if (sampleIndex == row.samples.lastIndex) "" else ",")
            }
            appendLine("      ]")
            append("    }")
            appendLine(if (rowIndex == rows.lastIndex) "" else ",")
        }
        appendLine("  ],")
        appendLine("  \"caveats\": [")
        appendLine("    \"Local SdkHttpClient double only; no AWS network or credential path is measured.\",")
        appendLine("    \"Throughput and heap values are same-JVM snapshots, not service-level guarantees.\"")
        appendLine("  ]")
        appendLine("}")
    }

    private fun percentile(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[ceil(sorted.size * 0.95).toInt().coerceAtLeast(1) - 1]
    }

    private fun memoryPools(): List<MemoryPoolMXBean> =
        ManagementFactory.getMemoryPoolMXBeans().filter { it.isValid }

    private fun peakHeapBytes(pools: List<MemoryPoolMXBean>): Long =
        pools.asSequence()
            .mapNotNull { pool -> runCatching { pool.peakUsage?.used }.getOrNull() }
            .maxOrNull()
            ?: 0L

    private fun requiredProperty(name: String): String =
        System.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: error("$name must be set for the local measurement")

    private class MeasurementHttpClient(
        private val certificate: ByteArray,
    ) : SdkHttpClient {

        private val requests = AtomicInteger()

        val requestCount: Int
            get() = requests.get()

        override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
            requests.incrementAndGet()
            return object : ExecutableHttpRequest {
                override fun call(): HttpExecuteResponse = HttpExecuteResponse.builder()
                    .response(SdkHttpFullResponse.builder().statusCode(200).build())
                    .responseBody(AbortableInputStream.create(ByteArrayInputStream(certificate)))
                    .build()

                override fun abort() = Unit
            }
        }

        override fun close() = Unit
    }

    private data class MeasurementRow(
        val scenario: String,
        val certificateFetches: Int,
        val samples: List<MeasurementSample>,
    )

    private data class MeasurementSample(
        val elapsedNanos: Long,
        val throughputOpsPerSecond: Double,
        val peakHeapBytes: Long,
    )

    private companion object {
        const val MEASUREMENT_PROPERTY = "bluetape4k.aws.sns.signature-measurement"
        const val OUTPUT_PROPERTY = "bluetape4k.aws.sns.signature-measurement.output"
        const val TOPIC_ARN = "arn:aws:sns:us-west-2:123456789012:issue-513-topic"
        const val BASE_CERTIFICATE_URL =
            "https://sns.us-west-2.amazonaws.com/SimpleNotificationService-issue-513.pem"
        const val WARMUP_OPERATIONS = 10
        const val MEASUREMENT_SAMPLES = 5
        const val MEASUREMENT_OPERATIONS = 200

        val notificationV2 = fixture("notification-v2.json")
        val signingCertificate = fixture("signing-cert.pem").toByteArray(StandardCharsets.UTF_8)

        fun certificateUrl(index: Int): String =
            "https://sns.us-west-2.amazonaws.com/issue-513-measurement-$index.pem"

        fun fixture(name: String): String =
            requireNotNull(
                SnsHttpMessageVerifierMeasurementTest::class.java.getResource("/sns-signature/$name"),
            )
                .readText(StandardCharsets.UTF_8)
    }
}
