package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.model.StreamStatus
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.awaitility.kotlin.await
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for [recordFlow] against LocalStack Kinesis.
 *
 * Tests run in declaration order. A single stream is created once (`@Order(1)`), records are
 * written (`@Order(3)`), and the flow is exercised in subsequent steps.
 *
 * `testinstance.lifecycle.default=per_class` (set in `junit-platform.properties`) ensures that
 * instance fields (`shardId`, `sequenceNumbers`) are shared across all test methods.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KinesisRecordFlowTest : AbstractKotlinKinesisTest() {

    companion object : KLoggingChannel() {
        private val STREAM_NAME = "flow-test-stream-" + Base58.randomString(6).lowercase()
        private const val RECORD_COUNT = 5
    }

    private lateinit var shardId: String
    private val sequenceNumbers = mutableListOf<String>()

    @Test
    @Order(1)
    fun `create test stream`() = runSuspendIO {
        withKinesisClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            client.createStream(STREAM_NAME, shardCount = 1)
            log.debug { "Stream created: $STREAM_NAME" }
        }
    }

    @Test
    @Order(2)
    fun `wait for stream to become ACTIVE`() = runSuspendIO {
        withKinesisClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            await.atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilSuspending {
                    val desc = client.describeStream(STREAM_NAME)
                    val status = desc.streamDescription?.streamStatus
                    if (status == StreamStatus.Active) {
                        shardId = desc.streamDescription!!.shards!!.first().shardId!!
                        log.debug { "Stream ACTIVE, shardId=$shardId" }
                    }
                    status == StreamStatus.Active
                }
            shardId.shouldNotBeEmpty()
        }
    }

    @Test
    @Order(3)
    fun `put test records into the stream`() = runSuspendIO {
        withKinesisClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            repeat(RECORD_COUNT) { i ->
                val response = client.putRecord(
                    streamName = STREAM_NAME,
                    partitionKey = "pk-$i",
                    data = "record-payload-$i".encodeToByteArray(),
                )
                sequenceNumbers.add(response.sequenceNumber)
                log.debug { "putRecord[$i] sequenceNumber=${response.sequenceNumber}" }
            }
            sequenceNumbers.size shouldBeEqualTo RECORD_COUNT
        }
    }

    @Test
    @Order(4)
    fun `recordFlow with TrimHorizon collects all records`() = runSuspendIO {
        withKinesisClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            val collected = withTimeout(30.seconds) {
                client.recordFlow(
                    streamName = STREAM_NAME,
                    shardId = shardId,
                    position = KinesisStartingPosition.TrimHorizon,
                ).take(RECORD_COUNT).toList()
            }

            collected.size shouldBeEqualTo RECORD_COUNT
            log.debug { "TrimHorizon collected ${collected.size} records" }

            collected.forEachIndexed { i, record ->
                val payload = record.data!!.decodeToString()
                log.debug { "  record[$i] seq=${record.sequenceNumber} payload=$payload" }
                payload shouldBeEqualTo "record-payload-$i"
            }
        }
    }

    @Test
    @Order(5)
    fun `recordFlow with AfterSequenceNumber skips earlier records`() = runSuspendIO {
        withKinesisClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            // Skip the first record — start after sequenceNumbers[0]
            val afterFirst = KinesisStartingPosition.AfterSequenceNumber(sequenceNumbers[0])
            val expectedCount = RECORD_COUNT - 1

            val collected = withTimeout(30.seconds) {
                client.recordFlow(
                    streamName = STREAM_NAME,
                    shardId = shardId,
                    position = afterFirst,
                ).take(expectedCount).toList()
            }

            collected.size shouldBeEqualTo expectedCount
            // First collected record must come after the skipped one
            collected[0].data!!.decodeToString() shouldBeEqualTo "record-payload-1"
            log.debug { "AfterSequenceNumber collected ${collected.size} records (skipped 1)" }
        }
    }

    @Test
    @Order(6)
    fun `recordFlow with AtSequenceNumber includes that record`() = runSuspendIO {
        withKinesisClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            // Start at the third record (index 2)
            val atThird = KinesisStartingPosition.AtSequenceNumber(sequenceNumbers[2])
            val expectedCount = RECORD_COUNT - 2

            val collected = withTimeout(30.seconds) {
                client.recordFlow(
                    streamName = STREAM_NAME,
                    shardId = shardId,
                    position = atThird,
                ).take(expectedCount).toList()
            }

            collected.size shouldBeEqualTo expectedCount
            collected[0].data!!.decodeToString() shouldBeEqualTo "record-payload-2"
            log.debug { "AtSequenceNumber collected ${collected.size} records starting from index 2" }
        }
    }

    @Test
    @Order(7)
    fun `delete test stream`() = runSuspendIO {
        withKinesisClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            client.deleteStream(STREAM_NAME)
            log.debug { "Stream deleted: $STREAM_NAME" }
        }
    }
}
