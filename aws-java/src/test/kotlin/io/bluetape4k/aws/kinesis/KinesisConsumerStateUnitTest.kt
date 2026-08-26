package io.bluetape4k.aws.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class KinesisConsumerStateUnitTest {

    private val key = KinesisShardKey("orders-v1", "orders-consumer", "shard-0")

    @Test
    fun `stale lease counter cannot overwrite checkpoint`() = runTest {
        val store = InMemoryKinesisCheckpointStore()
        val current = KinesisLease(key, "worker-new", 2)
        store.save(key, KinesisCheckpoint.Sequence("20"), current)

        assertFailsWith<KinesisLeaseLostException> {
            store.save(key, KinesisCheckpoint.Sequence("30"), KinesisLease(key, "worker-old", 1))
        }
        store.load(key) shouldBeEqualTo KinesisCheckpoint.Sequence("20")
    }

    @Test
    fun `metrics identifiers are deterministic redacted tokens`() {
        val token = redactedKinesisToken("stream-secret")
        KinesisFlowEvent.Batch(token, token, recordCount = 1)
        assertFailsWith<IllegalArgumentException> {
            KinesisFlowEvent.Batch("stream-secret", token, recordCount = 1)
        }
    }

    @Test
    fun `lease counter must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisLease(key, "worker", 0)
        }
    }

    @Test
    fun `sequence checkpoints are monotonic and shard end is terminal`() = runTest {
        val store = InMemoryKinesisCheckpointStore()
        val lease = KinesisLease(key, "worker", 1)
        store.save(key, KinesisCheckpoint.Sequence("20"), lease)

        assertFailsWith<KinesisCheckpointException> {
            store.save(key, KinesisCheckpoint.Sequence("19"), lease)
        }
        store.save(key, KinesisCheckpoint.ShardEnd, lease)
        assertFailsWith<KinesisCheckpointException> {
            store.save(key, KinesisCheckpoint.Sequence("21"), lease)
        }
    }

    @Test
    fun `lease acquisition supports expiry takeover and fenced release`() = runTest {
        val clock = MutableClock(Instant.parse("2026-08-27T00:00:00Z"))
        val store = InMemoryKinesisLeaseStore(clock = clock)
        val first = store.acquire(key, "worker-a", 1.seconds).shouldNotBeNull()
        store.acquire(key, "worker-b", 1.seconds).shouldBeNull()

        clock.now = clock.now.plusSeconds(2)
        val second = store.acquire(key, "worker-b", 1.seconds).shouldNotBeNull()
        store.release(first)
        store.renew(second, 1.seconds).shouldNotBeNull()
        store.acquire(key, "worker-a", 1.seconds).shouldBeNull()
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }
}
