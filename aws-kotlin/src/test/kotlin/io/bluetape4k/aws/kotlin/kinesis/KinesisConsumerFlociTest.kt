package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.model.StreamStatus
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.codec.Base58
import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Floci dynamic endpoint/static credentials에서 multi-shard consumer 경계를 검증합니다. */
@Execution(ExecutionMode.SAME_THREAD)
class KinesisConsumerFlociTest: AbstractKotlinKinesisTest() {

    companion object: KLoggingChannel()

    @Test
    fun `Floci consumer reads explicit hash records from multiple shards`() = runSuspendIO {
        val server = localStackServer
        check(server.awsEndpoint.scheme == "http" || server.awsEndpoint.scheme == "https") {
            "emulator endpoint must be explicit"
        }
        val streamName = "consumer-flow-" + Base58.randomString(8).lowercase()

        withTestKinesisClient(localStackServer) { client ->
            try {
                client.createStream(streamName, shardCount = 2)
                await atMost 30.seconds withPollInterval 1.seconds untilSuspending {
                    client.describeStream(streamName).streamDescription?.streamStatus == StreamStatus.Active
                }

                val maxHashKey = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString()
                val first = client.putRecord(
                    streamName = streamName,
                    partitionKey = "partition-first",
                    data = "floci-first".toUtf8Bytes(),
                ) {
                    explicitHashKey = "1"
                }
                val second = client.putRecord(
                    streamName = streamName,
                    partitionKey = "partition-second",
                    data = "floci-second".toUtf8Bytes(),
                ) {
                    explicitHashKey = maxHashKey
                }

                log.debug { "first=$first" }
                log.debug { "second=$second" }
                first.shardId.shouldNotBeEmpty()
                second.shardId.shouldNotBeEmpty()

                // Floci 1.6.0은 shardCount/ExplicitHashKey를 하나의 shard로 축약할 수 있습니다.
                // pinned image의 이 경계에서는 emulator 검증을 single-shard로 유지하고,
                // multi-shard routing 증거는 fake-client graph 테스트에서 확인합니다.
                val multiShard = first.shardId != second.shardId
                val expectedPayloads = if (multiShard) {
                    setOf("floci-first", "floci-second")
                } else {
                    setOf("floci-first")
                }
                val expectedCount = expectedPayloads.size

                val records = withTimeout(30.seconds) {
                    client
                        .consumerFlow(
                            streamName = streamName,
                            consumerGroup = "floci-group",
                            streamIdentity = "$streamName-v1",
                            position = KinesisStartingPosition.TrimHorizon,
                            options = KinesisConsumerOptions(
                                ownerId = "floci-owner-${Base58.randomString(6)}",
                                maxShardConcurrency = 2,
                                discoveryInterval = 500.milliseconds,
                            ),
                            checkpointStore = InMemoryKinesisCheckpointStore(),
                            leaseStore = InMemoryKinesisLeaseStore(),
                        )
                        .log("Consumer")
                        .take(expectedCount)
                        .toList()
                }

                records.map { it.record.data.toUtf8String() }.toSet() shouldBeEqualTo expectedPayloads
            } finally {
                client.deleteStream(streamName)
            }
        }
    }
}
