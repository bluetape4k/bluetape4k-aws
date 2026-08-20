package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldEndWith
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SnsCoroutinesTemplateAwsEmulatorTest {

    companion object {
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("sns", "sqs")
        }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SnsAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, { awsEmulator.getCredentialProvider() })
        .withPropertyValues(
            "bluetape4k.aws.sns.region=${awsEmulator.regionName}",
            "bluetape4k.aws.sns.endpoint-override=${awsEmulator.awsEndpoint}",
        )

    @Test
    fun `create find and publish standard topic through SnsOperations`() {
        contextRunner().run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            runSuspendIO {
                val topicName = "standard-${UUID.randomUUID()}"
                val topicArn = operations.createTopic(topicName)

                topicArn shouldEndWith ":$topicName"
                operations.findTopicArn(topicName) shouldBeEqualTo topicArn

                val published = operations.publish(
                    SnsPublishRequest(
                        topicArn = topicArn,
                        subject = "standard",
                        message = "hello sns",
                    )
                )
                published.messageId().shouldNotBeBlank()
            }
        }
    }

    @Test
    fun `create invalidates a previously cached negative lookup`() {
        contextRunner().run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            runSuspendIO {
                val topicName = "negative-${UUID.randomUUID()}"
                operations.findTopicArn(topicName).shouldBeNull()

                val topicArn = operations.createTopic(topicName)

                operations.findTopicArn(topicName) shouldBeEqualTo topicArn
            }
        }
    }

    @Test
    fun `create configured topic from properties`() {
        contextRunner()
            .withPropertyValues("bluetape4k.aws.sns.topics.configured.attributes.Environment=test")
            .run { context ->
                val operations = context.getBean(SnsOperations::class.java)

                runSuspendIO {
                    val topicArn = operations.createConfiguredTopic("configured")

                    topicArn shouldEndWith ":configured"
                    operations.findTopicArn("configured") shouldBeEqualTo topicArn
                }
            }
    }

    @Test
    fun `create FIFO topic and publish with message group`() {
        contextRunner().run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            runSuspendIO {
                val topicName = "fifo-${UUID.randomUUID()}.fifo"
                val topicArn = operations.createFifoTopic(
                    topicName = topicName,
                    contentBasedDeduplication = false,
                    fifoThroughputScope = SnsFifoThroughputScope.MESSAGE_GROUP,
                )

                val published = operations.publish(
                    SnsPublishRequest(
                        topicArn = topicArn,
                        message = "hello fifo sns",
                        messageGroupId = "orders",
                        messageDeduplicationId = UUID.randomUUID().toString(),
                    )
                )

                published.messageId().shouldNotBeBlank()
            }
        }
    }

    @Test
    fun `reject FIFO only publish fields for standard topic`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SnsPublishRequest(
                topicArn = "arn:aws:sns:us-east-1:000000000000:standard",
                message = "hello",
                messageGroupId = "orders",
            )
        }
        error.message.orEmpty() shouldContain "not allowed for standard topic"
    }

    @Test
    fun `reject blank publish fields`() {
        assertFailsWith<IllegalArgumentException> {
            SnsPublishRequest(topicArn = " ", message = "hello")
        }
        assertFailsWith<IllegalArgumentException> {
            SnsPublishRequest(topicArn = "arn:aws:sns:us-east-1:000000000000:standard", message = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            SnsPublishRequest(
                topicArn = "arn:aws:sns:us-east-1:000000000000:orders.fifo",
                message = "hello",
                messageGroupId = " ",
            )
        }
    }

    @Test
    fun `publish request copy revalidates FIFO contract`() {
        val request = SnsPublishRequest(
            topicArn = "arn:aws:sns:us-east-1:000000000000:orders.fifo",
            message = "hello",
            messageGroupId = "orders",
        )

        assertFailsWith<IllegalArgumentException> {
            request.copy(messageGroupId = null)
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(topicArn = "arn:aws:sns:us-east-1:000000000000:standard")
        }
    }

    @Test
    fun `propagate AWS publish errors`() {
        contextRunner().run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            val error = assertFailsWith<Exception> {
                runSuspendIO {
                    operations.publish(
                        SnsPublishRequest(
                            topicArn = "arn:aws:sns:${awsEmulator.regionName}:000000000000:missing",
                            message = "missing",
                        )
                    )
                }
            }
            error.message.orEmpty() shouldContain "Topic"
        }
    }

    @Test
    fun `publish message to SQS subscription`() {
        contextRunner().run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            runSuspendIO {
                val sqs = sqsAsyncClient()
                try {
                    val topicArn = operations.createTopic("fanout-${UUID.randomUUID()}")
                    val queueUrl = sqs.createQueue {
                        it.queueName("fanout-${UUID.randomUUID()}")
                    }.await().queueUrl()
                    val queueArn = requireNotNull(
                        sqs.getQueueAttributes {
                            it.queueUrl(queueUrl)
                            it.attributeNames(QueueAttributeName.QUEUE_ARN)
                        }.await().attributes()[QueueAttributeName.QUEUE_ARN]
                    ) {
                        "QueueArn attribute must be returned by the AWS emulator."
                    }

                    val policy = queuePolicy(queueArn = queueArn, topicArn = topicArn)
                    sqs.setQueueAttributes {
                        it.queueUrl(queueUrl)
                        it.attributes(mapOf(QueueAttributeName.POLICY to policy))
                    }.await()

                    context.getBean(software.amazon.awssdk.services.sns.SnsAsyncClient::class.java)
                        .subscribe {
                            it.topicArn(topicArn)
                            it.protocol("sqs")
                            it.endpoint(queueArn)
                            it.returnSubscriptionArn(true)
                        }
                        .await()

                    operations.publish(SnsPublishRequest(topicArn = topicArn, message = "fanout"))

                    val received = sqs.receiveMessage {
                        it.queueUrl(queueUrl)
                        it.maxNumberOfMessages(1)
                        it.waitTimeSeconds(5)
                    }.await().messages()

                    received shouldHaveSize 1
                    received.single().body() shouldContain "fanout"
                } finally {
                    sqs.close()
                }
            }
        }
    }

    private fun sqsAsyncClient(): SqsAsyncClient =
        SqsAsyncClient.builder()
            .credentialsProvider(awsEmulator.getCredentialProvider())
            .region(Region.of(awsEmulator.regionName))
            .endpointOverride(awsEmulator.awsEndpoint)
            .build()

    private fun queuePolicy(queueArn: String, topicArn: String): String =
        """
        {
          "Version":"2012-10-17",
          "Statement":[{
            "Effect":"Allow",
            "Principal":"*",
            "Action":"sqs:SendMessage",
            "Resource":"$queueArn",
            "Condition":{"ArnEquals":{"aws:SourceArn":"$topicArn"}}
          }]
        }
        """.trimIndent()

    @Test
    @EnabledIfSystemProperty(named = "bluetape4k.aws.sns.real-measurement", matches = "true")
    fun `measure actual SNS batch publisher and write Floci artifacts`() = runSuspendIO {
        measureFlociBatchRows()
    }

    private suspend fun measureFlociBatchRows() {
        val client = SnsAsyncClient.builder()
            .credentialsProvider(awsEmulator.getCredentialProvider())
            .region(Region.of(awsEmulator.regionName))
            .endpointOverride(awsEmulator.awsEndpoint)
            .build()
        var topicArn: String? = null
        var primaryFailure: Throwable? = null
        fun recordFailure(cause: Throwable) {
            val failure = primaryFailure
            if (failure == null) {
                primaryFailure = cause
            } else {
                failure.addSuppressed(cause)
            }
        }

        try {
            val createdTopicArn = client.createTopic {
                it.name("measure-${Base58.randomString(16)}")
            }.await().topicArn()
            topicArn = createdTopicArn

            val rows = buildList {
                for (scenario in SNS_MEASUREMENT_SCENARIOS) {
                    for (entryCount in SNS_MEASUREMENT_ENTRY_COUNTS) {
                        for (maxInFlight in SNS_MEASUREMENT_MAX_IN_FLIGHT) {
                            add(
                                measureFlociRow(
                                    client = client,
                                    topicArn = createdTopicArn,
                                    scenario = scenario,
                                    entryCount = entryCount,
                                    maxInFlight = maxInFlight,
                                )
                            )
                        }
                    }
                }
            }
            rows.forEach(::assertFlociRow)
            writeFlociArtifacts(rows)
        } catch (cause: Throwable) {
            primaryFailure = cause
        } finally {
            try {
                topicArn?.let { arn ->
                    client.deleteTopic { it.topicArn(arn) }.await()
                }
            } catch (cause: Throwable) {
                recordFailure(cause)
            } finally {
                try {
                    client.close()
                } catch (cause: Throwable) {
                    recordFailure(cause)
                }
            }
        }
        primaryFailure?.let { throw it }
    }

    private suspend fun measureFlociRow(
        client: SnsAsyncClient,
        topicArn: String,
        scenario: String,
        entryCount: Int,
        maxInFlight: Int,
    ): FlociMeasurementRow {
        repeat(SNS_MEASUREMENT_WARMUPS) {
            executeFlociSample(client, topicArn, scenario, entryCount, maxInFlight)
        }
        val samples = buildList {
            repeat(SNS_MEASUREMENT_REPETITIONS) {
                add(executeFlociSample(client, topicArn, scenario, entryCount, maxInFlight))
            }
        }
        val durations = samples.map { it.durationNanos }
        val throughputs = samples.map { it.throughputMessagesPerSecond }
        return FlociMeasurementRow(
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
            resultEntries = samples.maxOf { it.resultEntries },
        )
    }

    private suspend fun executeFlociSample(
        client: SnsAsyncClient,
        topicArn: String,
        scenario: String,
        entryCount: Int,
        maxInFlight: Int,
    ): FlociMeasurementSample {
        val entries = (1..entryCount).map { index ->
            SnsPublishBatchEntry(
                id = "entry-$index-${Base58.randomString(16)}",
                message = "message-$index-${Base58.randomString(16)}",
            )
        }
        val telemetry = FlociMeasurementTelemetry()
        val memoryPools = memoryPools()
        memoryPools.forEach { pool ->
            if (pool.isValid) {
                pool.resetPeakUsage()
            }
        }
        val requestTopicArn = if (scenario == "success") {
            topicArn
        } else {
            "arn:aws:sns:${awsEmulator.regionName}:000000000000:missing-${Base58.randomString(16)}"
        }
        val request = SnsPublishBatchRequest(requestTopicArn, entries)
        val startedAt = System.nanoTime()
        val resultEntries = executeFlociPublish(
            client = client,
            request = request,
            requestTopicArn = requestTopicArn,
            maxInFlight = maxInFlight,
            telemetry = telemetry,
            entryCount = entryCount,
        )
        val durationNanos = System.nanoTime() - startedAt
        return FlociMeasurementSample(
            durationNanos = durationNanos,
            throughputMessagesPerSecond = entryCount * 1_000_000_000.0 / durationNanos,
            peakHeapBytes = peakHeapBytes(memoryPools),
            activeAfter = telemetry.active.get(),
            maxActive = telemetry.maxActive.get(),
            observedChunks = telemetry.observedChunks.get(),
            completedEntries = telemetry.completedEntries.get(),
            completedEntryIds = telemetry.completedEntryIds.get(),
            resultEntries = resultEntries,
        )
    }

    private suspend fun executeFlociPublish(
        client: SnsAsyncClient,
        request: SnsPublishBatchRequest,
        requestTopicArn: String,
        maxInFlight: Int,
        telemetry: FlociMeasurementTelemetry,
        entryCount: Int,
    ): Int {
        return try {
            val result = SnsBatchExecutor(
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
            result.successful.size + result.failed.size
        } catch (cause: SnsBatchTransportException) {
            cause.completedEntryIds.size shouldBeLessOrEqualTo entryCount
            0
        }
    }

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

    private fun assertFlociRow(row: FlociMeasurementRow) {
        row.activeAfter shouldBeEqualTo 0
        row.maxActive shouldBeLessOrEqualTo row.maxInFlight
        row.observedChunks shouldBeLessOrEqualTo (row.entryCount + 9) / 10
        if (row.scenario == "success") {
            row.observedChunks shouldBeEqualTo (row.entryCount + 9) / 10
            row.completedEntries shouldBeEqualTo row.entryCount
            row.completedEntryIds shouldBeEqualTo row.entryCount
            row.resultEntries shouldBeEqualTo row.entryCount
        } else {
            row.completedEntries shouldBeLessOrEqualTo row.entryCount
            row.completedEntryIds shouldBeLessOrEqualTo row.entryCount
            row.resultEntries shouldBeEqualTo 0
        }
        (row.peakHeapBytes >= 0L).shouldBeTrue()
    }

    private fun writeFlociArtifacts(rows: List<FlociMeasurementRow>) {
        val output = Path.of(
            System.getProperty(
                "bluetape4k.aws.sns.measurement.output",
                "build/reports/sns-batch/floci",
            )
        )
        Files.createDirectories(output)
        Files.writeString(
            output.resolve("throughput.json"),
            rows.joinToString(prefix = "[\n", postfix = "\n]\n") { row ->
                jmhRecord(row, mode = "thrpt")
            },
        )
        Files.writeString(
            output.resolve("latency.json"),
            rows.joinToString(prefix = "[\n", postfix = "\n]\n") { row ->
                jmhRecord(row, mode = "avgt")
            },
        )
        val endpoint = awsEmulator.awsEndpoint.toString().replace("\"", "\\\"")
        Files.writeString(
            output.resolve("environment.json"),
            """{
              "schema_version": 1,
              "backend": "floci",
              "endpoint": "$endpoint",
              "region": "${awsEmulator.regionName}",
              "warmups": $SNS_MEASUREMENT_WARMUPS,
              "repetitions": $SNS_MEASUREMENT_REPETITIONS,
              "matrix": {
                "entryCount": [1, 10, 11, 20, 21, 100],
                "maxInFlightBatches": [1, 2, 4],
                "scenario": ["success", "transport"]
              }
            }
            """.trimIndent() + "\n",
        )
    }

    private fun jmhRecord(row: FlociMeasurementRow, mode: String): String {
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
            "resultEntries": {"score": ${row.resultEntries}},
            "peakHeapBytes": {"score": ${row.peakHeapBytes}}
          }
        }""".trimIndent()
    }

    private fun toSdkEntry(entry: SnsPublishBatchEntry): PublishBatchRequestEntry =
        PublishBatchRequestEntry.builder()
            .id(entry.id)
            .message(entry.message)
            .build()

    private fun memoryPools(): List<MemoryPoolMXBean> =
        ManagementFactory.getMemoryPoolMXBeans().filter { it.isValid }

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

    private class FlociMeasurementTelemetry {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val observedChunks = AtomicInteger()
        val completedEntries = AtomicInteger()
        val completedEntryIds = AtomicInteger()
    }

    private data class FlociMeasurementSample(
        val durationNanos: Long,
        val throughputMessagesPerSecond: Double,
        val peakHeapBytes: Long,
        val activeAfter: Int,
        val maxActive: Int,
        val observedChunks: Int,
        val completedEntries: Int,
        val completedEntryIds: Int,
        val resultEntries: Int,
    )

    private data class FlociMeasurementRow(
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
        val resultEntries: Int,
    )
}

private const val SNS_MEASUREMENT_WARMUPS: Int = 1
private const val SNS_MEASUREMENT_REPETITIONS: Int = 3
private val SNS_MEASUREMENT_ENTRY_COUNTS = listOf(1, 10, 11, 20, 21, 100)
private val SNS_MEASUREMENT_MAX_IN_FLIGHT = listOf(1, 2, 4)
private val SNS_MEASUREMENT_SCENARIOS = listOf("success", "transport")
