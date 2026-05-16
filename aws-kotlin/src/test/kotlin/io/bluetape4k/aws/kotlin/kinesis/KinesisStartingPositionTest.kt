package io.bluetape4k.aws.kotlin.kinesis

import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.Instant
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import kotlin.test.assertFailsWith

class KinesisStartingPositionTest {

    companion object : KLogging()

    private fun roundtrip(value: KinesisStartingPosition): KinesisStartingPosition {
        val bytes = ByteArrayOutputStream().also { bos ->
            ObjectOutputStream(bos).use { it.writeObject(value) }
        }.toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as KinesisStartingPosition
    }

    @Test
    fun `TrimHorizon singleton survives serialization roundtrip`() {
        val deserialized = roundtrip(KinesisStartingPosition.TrimHorizon)
        (deserialized === KinesisStartingPosition.TrimHorizon).shouldBeTrue()
    }

    @Test
    fun `Latest singleton survives serialization roundtrip`() {
        val deserialized = roundtrip(KinesisStartingPosition.Latest)
        (deserialized === KinesisStartingPosition.Latest).shouldBeTrue()
    }

    @Test
    fun `AtSequenceNumber equality and serialization`() {
        val pos = KinesisStartingPosition.AtSequenceNumber("49590338271490256608559692538361571095921575989136588898")
        pos.sequenceNumber shouldBeEqualTo "49590338271490256608559692538361571095921575989136588898"

        val deserialized = roundtrip(pos) as KinesisStartingPosition.AtSequenceNumber
        deserialized.sequenceNumber shouldBeEqualTo pos.sequenceNumber
        deserialized shouldBeEqualTo pos
    }

    @Test
    fun `AtSequenceNumber rejects blank sequenceNumber`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisStartingPosition.AtSequenceNumber("")
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisStartingPosition.AtSequenceNumber("   ")
        }
    }

    @Test
    fun `AfterSequenceNumber equality and serialization`() {
        val pos = KinesisStartingPosition.AfterSequenceNumber("49590338271490256608559692540925702759324208523137515522")
        pos.sequenceNumber shouldBeEqualTo "49590338271490256608559692540925702759324208523137515522"

        val deserialized = roundtrip(pos) as KinesisStartingPosition.AfterSequenceNumber
        deserialized.sequenceNumber shouldBeEqualTo pos.sequenceNumber
        deserialized shouldBeEqualTo pos
    }

    @Test
    fun `AfterSequenceNumber rejects blank sequenceNumber`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisStartingPosition.AfterSequenceNumber("")
        }
    }

    @Test
    fun `AtTimestamp equality and serialization`() {
        val ts = Instant.parse("2024-01-15T12:00:00Z")
        val pos = KinesisStartingPosition.AtTimestamp(ts)
        pos.timestamp shouldBeEqualTo ts

        val deserialized = roundtrip(pos) as KinesisStartingPosition.AtTimestamp
        deserialized.timestamp shouldBeEqualTo ts
        deserialized shouldBeEqualTo pos
    }

    @Test
    fun `AtTimestamp preserves nanosecond precision`() {
        val ts = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L)
        val pos = KinesisStartingPosition.AtTimestamp(ts)
        pos.timestamp.epochSecond shouldBeEqualTo 1_700_000_000L
        pos.timestamp.nano shouldBeEqualTo 123_456_789

        val deserialized = roundtrip(pos) as KinesisStartingPosition.AtTimestamp
        deserialized.timestamp.nano shouldBeEqualTo 123_456_789
    }

    @Test
    fun `sealed variants are exhaustive in when`() {
        fun label(p: KinesisStartingPosition): String = when (p) {
            is KinesisStartingPosition.TrimHorizon       -> "trim"
            is KinesisStartingPosition.Latest            -> "latest"
            is KinesisStartingPosition.AtSequenceNumber  -> "at"
            is KinesisStartingPosition.AfterSequenceNumber -> "after"
            is KinesisStartingPosition.AtTimestamp       -> "ts"
        }
        label(KinesisStartingPosition.TrimHorizon) shouldBeEqualTo "trim"
        label(KinesisStartingPosition.Latest) shouldBeEqualTo "latest"
        label(KinesisStartingPosition.AtSequenceNumber("seq")) shouldBeEqualTo "at"
        label(KinesisStartingPosition.AfterSequenceNumber("seq")) shouldBeEqualTo "after"
        label(KinesisStartingPosition.AtTimestamp(Instant.now())) shouldBeEqualTo "ts"
    }
}
