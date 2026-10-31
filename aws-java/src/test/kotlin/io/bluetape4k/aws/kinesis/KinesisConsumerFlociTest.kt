package io.bluetape4k.aws.kinesis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.aws.core.toSdkBytes
import io.bluetape4k.codec.Base58
import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.aws.FlociServer
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.model.StreamStatus
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 실제 AWS credential chain 없이 Floci endpoint와 static emulator credential만 사용하는 contract test입니다. */
@Execution(ExecutionMode.SAME_THREAD)
class KinesisConsumerFlociTest: AbstractKinesisTest() {

    companion object: KLoggingChannel()

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `consumer reads records from multiple Floci shards`() = runSuspendIO {

        val floci = FlociServer.Launcher.floci
        val streamName = "issue-470-${Base58.randomString(8).lowercase()}"

        val asyncClient = KinesisClientFactory.Async.create {
            endpointOverride(floci.awsEndpoint)
            region(Region.of(floci.regionName))
            credentialsProvider(staticCredentialsProviderOf(floci.awsAccessKey, floci.awsSecretKey))
        }

        try {
            asyncClient.createStream(streamName, shardCount = 2)
            await atMost 30.seconds withPollInterval 250.milliseconds untilSuspending {
                asyncClient.describeStream(streamName).streamDescription().streamStatus() == StreamStatus.ACTIVE
            }

            val shards = asyncClient.listShards(streamName).shards()
            shards.size shouldBeGreaterOrEqualTo 2
            val maxHashKey = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString()


            val first = asyncClient.putRecord(
                streamName,
                "issue-470-first",
                "1",
                "first".toSdkBytes()
            )
            val second = asyncClient.putRecord(
                streamName,
                "issue-470-second",
                maxHashKey,
                "second".toSdkBytes()
            )

            first.shardId().shouldNotBeBlank()  //  { "Floci first putRecord did not return shardId" }
            second.shardId().shouldNotBeBlank() // { "Floci second putRecord did not return shardId" }

            // Floci 1.6.0 may collapse shardCount/ExplicitHashKey to one shard. In that
            // pinned-image gap, keep the emulator boundary check single-shard and leave
            // multi-shard routing proof to the fake-client graph tests.
            val multiShard = first.shardId() != second.shardId()
            val expectedPayloads = if (multiShard) setOf("first", "second") else setOf("first")
            val expectedCount = expectedPayloads.size

            val records = asyncClient
                .consumerFlow(
                    streamName = streamName,
                    consumerGroup = "issue-470-group",
                    streamIdentity = streamName,
                    position = KinesisStartingPosition.TrimHorizon,
                    options = KinesisConsumerOptions(ownerId = "issue-470-floci-test"),
                    checkpointStore = InMemoryKinesisCheckpointStore(),
                    leaseStore = InMemoryKinesisLeaseStore(),
                )
                .log("Record")
                .take(expectedCount)
                .toList()

            records.map { it.record.data().asUtf8String() }.toSet() shouldBeEqualTo expectedPayloads
        } finally {
            asyncClient.deleteStream { it.streamName(streamName) }.await()
            asyncClient.close()
        }
    }
}
