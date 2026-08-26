package io.bluetape4k.aws.kotlin.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotBeEqualTo
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** Kinesis consumer의 key, lease, checkpoint 계약을 고정하는 단위 테스트입니다. */
class KinesisConsumerStateUnitTest {

    @Test
    fun `canonical key distinguishes tuple delimiters`() {
        val first = KinesisShardKey("ab", "c", "d")
        val second = KinesisShardKey("a", "bc", "d")

        first.canonicalValue.shouldNotBeNull()
        first.canonicalValue shouldNotBeEqualTo second.canonicalValue
    }

    @Test
    fun `invalid key values are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisShardKey(" ", "group", "shard")
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisShardKey("stream\u0000", "group", "shard")
        }
    }

    @Test
    fun `checkpoint and lease values preserve serialization contract`() {
        val key = KinesisShardKey("stream", "group", "shard")
        KinesisCheckpoint.Sequence("10").sequenceNumber shouldBeEqualTo "10"
        KinesisCheckpoint.ShardEnd.toString() shouldBeEqualTo "ShardEnd"
        val lease = KinesisLease(key, "owner", leaseCounter = 1)
        lease.key shouldBeEqualTo key
        assertFailsWith<IllegalArgumentException> {
            KinesisLease(key, "owner", leaseCounter = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisLease(key, "owner", leaseCounter = 0)
        }
    }

    @Test
    fun `metrics identifiers are deterministic redacted tokens`() {
        val token = KinesisFlowEvent.redactedToken("stream-secret").also { it.shouldNotBeNull() }
        token.length shouldBeEqualTo KinesisFlowEvent.MAX_TOKEN_LENGTH
        token shouldNotBeEqualTo "stream-secret"
        KinesisFlowEvent.Observation(
            eventKind = KinesisFlowEvent.EventKind.SHARD,
            outcome = KinesisFlowEvent.Outcome.STARTED,
            streamToken = token,
        )
        assertFailsWith<IllegalArgumentException> {
            KinesisFlowEvent.Observation(
                eventKind = KinesisFlowEvent.EventKind.SHARD,
                outcome = KinesisFlowEvent.Outcome.STARTED,
                streamToken = "stream-secret",
            )
        }
    }

    @Test
    fun `in memory stores fence stale checkpoint and lease`() = runTest {
        val key = KinesisShardKey("stream", "group", "shard")
        val leases = InMemoryKinesisLeaseStore()
        val checkpoints = InMemoryKinesisCheckpointStore()
        val owner = leases.acquire(key, "owner", 60.seconds).shouldNotBeNull()

        checkpoints.save(key, KinesisCheckpoint.Sequence("20"), owner)
        assertFailsWith<KinesisLeaseLostException> {
            checkpoints.save(key, KinesisCheckpoint.Sequence("30"), owner.copy(ownerId = "other"))
        }
        checkpoints.load(key) shouldBeEqualTo KinesisCheckpoint.Sequence("20")

        leases.renew(owner, 60.seconds).shouldNotBeNull()
        leases.release(owner)
        leases.acquire(key, "other", 60.seconds).shouldNotBeNull()
        leases.release(owner)
        leases.acquire(key, "other", 60.seconds).shouldNotBeNull()
    }

    @Test
    fun `options enforce owner and timing invariants`() {
        val options = KinesisConsumerOptions(ownerId = "owner")
        options.maxShardConcurrency shouldBeEqualTo 4
        options.discoveryInterval shouldBeEqualTo 5.seconds
        assertFailsWith<IllegalArgumentException> {
            KinesisConsumerOptions(ownerId = "", leaseDuration = 1.seconds, leaseRenewInterval = 1.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisConsumerOptions(ownerId = "owner", leaseDuration = 1.seconds, leaseRenewInterval = 1.seconds)
        }
    }
}
