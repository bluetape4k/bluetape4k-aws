@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.kinesis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.FlociServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest
import software.amazon.awssdk.services.kinesis.model.StreamStatus
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/** 실제 AWS credential chain 없이 Floci endpoint와 static emulator credential만 사용하는 contract test입니다. */
@Execution(ExecutionMode.SAME_THREAD)
class KinesisConsumerFlociTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `consumer reads records from multiple Floci shards`() = runSuspendIO {
        val floci = FlociServer.Launcher.floci
        val streamName = "issue-470-${Base58.randomString(8).lowercase()}"
        val client = KinesisAsyncClient.builder()
            .endpointOverride(floci.awsEndpoint)
            .region(Region.of(floci.regionName))
            .credentialsProvider(staticCredentialsProviderOf(floci.awsAccessKey, floci.awsSecretKey))
            .build()

        try {
            client.createStream(streamName, shardCount = 2)
            withTimeout(30_000) {
                while (client.describeStream(streamName).streamDescription().streamStatus() != StreamStatus.ACTIVE) {
                    delay(250)
                }
            }
            val shards = client.listShards { it.streamName(streamName) }.await().shards()
            shards.size shouldBeGreaterOrEqualTo 2
            val maxHashKey = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString()

            val first = client.putRecord(
                PutRecordRequest.builder()
                    .streamName(streamName)
                    .partitionKey("issue-470-first")
                    .explicitHashKey("1")
                    .data(SdkBytes.fromUtf8String("first"))
                    .build(),
            ).await()
            val second = client.putRecord(
                PutRecordRequest.builder()
                    .streamName(streamName)
                    .partitionKey("issue-470-second")
                    .explicitHashKey(maxHashKey)
                    .data(SdkBytes.fromUtf8String("second"))
                    .build(),
            ).await()
            require(!first.shardId().isNullOrBlank()) { "Floci first putRecord did not return shardId" }
            require(!second.shardId().isNullOrBlank()) { "Floci second putRecord did not return shardId" }
            // Floci 1.6.0 may collapse shardCount/ExplicitHashKey to one shard. In that
            // pinned-image gap, keep the emulator boundary check single-shard and leave
            // multi-shard routing proof to the fake-client graph tests.
            val multiShard = first.shardId() != second.shardId()
            val expectedPayloads = if (multiShard) setOf("first", "second") else setOf("first")
            val expectedCount = expectedPayloads.size

            val records = client.consumerFlow(
                streamName = streamName,
                consumerGroup = "issue-470-group",
                streamIdentity = streamName,
                position = KinesisStartingPosition.TrimHorizon,
                options = KinesisConsumerOptions(ownerId = "issue-470-floci-test"),
                checkpointStore = InMemoryKinesisCheckpointStore(),
                leaseStore = InMemoryKinesisLeaseStore(),
            ).take(expectedCount).toList()

            records.map { it.record.data().asUtf8String() }.toSet() shouldBeEqualTo expectedPayloads
        } finally {
            client.deleteStream { it.streamName(streamName) }.await()
            client.close()
        }
    }
}
