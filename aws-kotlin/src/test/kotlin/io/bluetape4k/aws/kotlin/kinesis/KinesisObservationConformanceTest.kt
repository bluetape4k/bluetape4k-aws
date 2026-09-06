package io.bluetape4k.aws.kotlin.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import java.util.Properties

class KinesisObservationConformanceTest {

    private val contract = loadContract()
    private val streamToken = KinesisFlowEvent.redactedToken(contract.required("token.input.stream"))
    private val shardToken = KinesisFlowEvent.redactedToken(contract.required("token.input.shard"))
    private val ownerToken = KinesisFlowEvent.redactedToken(contract.required("token.input.principal"))

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
        observation(KinesisFlowEvent.EventKind.SHARD, streamToken = streamToken)
            .toCanonicalObservations().single().streamToken shouldBeEqualTo contract.required("token.expected.stream")
        observation(KinesisFlowEvent.EventKind.SHARD, shardToken = shardToken)
            .toCanonicalObservations().single().shardToken shouldBeEqualTo contract.required("token.expected.shard")
        observation(KinesisFlowEvent.EventKind.LEASE, ownerToken = ownerToken)
            .toCanonicalObservations().single().ownerToken shouldBeEqualTo contract.required("token.expected.principal")

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
            observation(KinesisFlowEvent.EventKind.LEASE, KinesisFlowEvent.Outcome.STARTED),
        )
        assertVector("mapping.lease.renew", observation(KinesisFlowEvent.EventKind.LEASE))
        assertVector(
            "mapping.lease.loss",
            observation(
                KinesisFlowEvent.EventKind.LEASE,
                KinesisFlowEvent.Outcome.LOST,
                KinesisFlowEvent.Reason.LEASE_LOST,
            ),
        )
        assertVector(
            "mapping.shard.start",
            observation(KinesisFlowEvent.EventKind.SHARD, KinesisFlowEvent.Outcome.STARTED),
        )
        assertVector(
            "mapping.shard.cancelled",
            observation(
                KinesisFlowEvent.EventKind.SHARD,
                KinesisFlowEvent.Outcome.FAILED,
                KinesisFlowEvent.Reason.CANCELLED,
            ),
        )
        assertVector("mapping.batch.read", observation(KinesisFlowEvent.EventKind.BATCH, count = 7))
        assertVector("mapping.record.saved", observation(KinesisFlowEvent.EventKind.RECORD, count = 1))
        assertVector(
            "mapping.shard.end",
            listOf(
                observation(KinesisFlowEvent.EventKind.CHECKPOINT, reason = KinesisFlowEvent.Reason.SHARD_END),
                observation(KinesisFlowEvent.EventKind.SHARD),
            ).flatMap(KinesisFlowEvent::toCanonicalObservations),
        )
        assertVector(
            "mapping.retry.iterator",
            observation(
                KinesisFlowEvent.EventKind.RETRY,
                reason = KinesisFlowEvent.Reason.ITERATOR_EXPIRED,
                retryClass = KinesisFlowEvent.RetryClass.ITERATOR,
                retryCount = 2,
            ),
        )
        assertVector("mapping.discovery", observation(KinesisFlowEvent.EventKind.DISCOVERY, count = 3))
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

    private fun observation(
        eventKind: KinesisFlowEvent.EventKind,
        outcome: KinesisFlowEvent.Outcome = KinesisFlowEvent.Outcome.SUCCESS,
        reason: KinesisFlowEvent.Reason? = null,
        retryClass: KinesisFlowEvent.RetryClass? = null,
        streamToken: String? = null,
        shardToken: String? = null,
        ownerToken: String? = null,
        count: Int? = null,
        retryCount: Int? = null,
    ): KinesisFlowEvent.Observation = KinesisFlowEvent.Observation(
        eventKind = eventKind,
        outcome = outcome,
        reason = reason,
        retryClass = retryClass,
        streamToken = streamToken,
        shardToken = shardToken,
        ownerToken = ownerToken,
        count = count,
        retryCount = retryCount,
    )

    private fun assertVector(key: String, event: KinesisFlowEvent) {
        assertVector(key, event.toCanonicalObservations())
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
