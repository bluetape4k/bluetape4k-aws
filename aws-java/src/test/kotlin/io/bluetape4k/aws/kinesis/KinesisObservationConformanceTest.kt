package io.bluetape4k.aws.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import java.util.Properties

class KinesisObservationConformanceTest {

    private val contract = loadContract()
    private val streamToken = redactedKinesisToken(contract.required("token.input.stream"))
    private val shardToken = redactedKinesisToken(contract.required("token.input.shard"))
    private val ownerToken = redactedKinesisToken(contract.required("token.input.owner"))

    @Test
    fun `canonical schema matches the shared manifest`() {
        KinesisCanonicalObservation.SCHEMA_VERSION shouldBeEqualTo contract.required("schema.version").toInt()
        KinesisCanonicalObservation.EVENT_KINDS shouldBeEqualTo contract.csv("event.kinds")
        KinesisCanonicalObservation.OUTCOMES shouldBeEqualTo contract.csv("outcomes")
        KinesisCanonicalObservation.REASONS shouldBeEqualTo contract.csv("reasons")
        KinesisCanonicalObservation.RETRY_CLASSES shouldBeEqualTo contract.csv("retry.classes")
        KinesisCanonicalObservation.TOKEN_LENGTH shouldBeEqualTo contract.required("token.length").toInt()
        KinesisCanonicalObservation.MAX_COUNT shouldBeEqualTo contract.required("count.max").toInt()
    }

    @Test
    fun `token and bounds match the shared vectors`() {
        streamToken shouldBeEqualTo contract.required("token.expected.stream")
        shardToken shouldBeEqualTo contract.required("token.expected.shard")
        ownerToken shouldBeEqualTo contract.required("token.expected.owner")

        KinesisCanonicalObservation("batch", "success", count = KinesisCanonicalObservation.MAX_COUNT)
        assertFailsWith<IllegalArgumentException> {
            KinesisCanonicalObservation("batch", "success", count = KinesisCanonicalObservation.MAX_COUNT + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisCanonicalObservation("batch", "success", shardToken = "raw-shard")
        }
    }

    @Test
    fun `legacy events map to the shared lifecycle vectors`() {
        assertVector(
            "mapping.lease.acquire",
            KinesisFlowEvent.Lease(streamToken, shardToken, ownerToken, outcome = "acquired")
                .toCanonicalObservations(),
        )
        assertVector(
            "mapping.lease.renew",
            KinesisFlowEvent.Lease(streamToken, shardToken, ownerToken, outcome = "renewed")
                .toCanonicalObservations(),
        )
        assertVector(
            "mapping.lease.loss",
            KinesisFlowEvent.Lease(streamToken, shardToken, ownerToken, outcome = "lost", reason = "lease_lost")
                .toCanonicalObservations(),
        )
        assertVector(
            "mapping.shard.start",
            KinesisFlowEvent.Shard(streamToken, shardToken, ownerToken, "shard", "started")
                .toCanonicalObservations(),
        )
        assertVector(
            "mapping.shard.cancelled",
            KinesisFlowEvent.Shard(streamToken, shardToken, ownerToken, "shard", "failed", reason = "cancelled")
                .toCanonicalObservations(),
        )
        assertVector(
            "mapping.batch.read",
            KinesisFlowEvent.Batch(streamToken, shardToken, recordCount = 7).toCanonicalObservations(),
        )
        assertVector(
            "mapping.record.saved",
            KinesisFlowEvent.Checkpoint(streamToken, shardToken, streamToken).toCanonicalObservations(),
        )
        assertVector(
            "mapping.shard.end",
            KinesisFlowEvent.Shard(streamToken, shardToken, ownerToken, "shard", "completed")
                .toCanonicalObservations(),
        )
        assertVector(
            "mapping.retry.iterator",
            KinesisFlowEvent.Retry(
                streamToken,
                shardToken,
                attempt = 2,
                reason = "iterator_expired",
                retryClass = "iterator",
            )
                .toCanonicalObservations(),
        )
        assertVector(
            "mapping.discovery",
            KinesisFlowEvent.Discovery(streamToken, page = 1, shardCount = 3).toCanonicalObservations(),
        )
    }

    @Test
    fun `canonical shape exposes no raw or secret-bearing fields`() {
        val names = KinesisCanonicalObservation::class.java.declaredFields.map { it.name }.toSet()
        listOf("payload", "sequenceToken", "exception", "message", "streamName", "shardId", "ownerId")
            .forEach { field -> names.contains(field).shouldBeFalse() }
    }

    @Test
    fun `validation failures do not echo rejected values`() {
        val secret = "customer-secret-stream-name"
        val failure = assertFailsWith<IllegalArgumentException> {
            KinesisCanonicalObservation(secret, "success")
        }

        failure.message?.contains(secret).shouldBeFalse()
    }

    private fun assertVector(key: String, actual: List<KinesisCanonicalObservation>) {
        actual.map { it.vectorValue() } shouldBeEqualTo contract.required(key).split(';')
    }

    private fun KinesisCanonicalObservation.vectorValue(): String =
        listOf(eventKind, outcome, reason.orEmpty(), retryClass.orEmpty()).joinToString("|")

    private fun loadContract(): Properties = Properties().apply {
        KinesisObservationConformanceTest::class.java.classLoader
            .getResourceAsStream("kinesis-observation-v1.properties")
            .use { input -> load(requireNotNull(input)) }
    }

    private fun Properties.required(key: String): String = requireNotNull(getProperty(key)) { "Missing $key" }
    private fun Properties.csv(key: String): Set<String> = required(key).split(',').toSet()
}
