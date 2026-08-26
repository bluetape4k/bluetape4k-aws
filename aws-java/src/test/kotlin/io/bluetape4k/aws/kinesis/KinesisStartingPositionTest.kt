package io.bluetape4k.aws.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.Instant
import org.junit.jupiter.api.Test

class KinesisStartingPositionTest {

    private fun roundtrip(value: KinesisStartingPosition): KinesisStartingPosition {
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(value) }
        }.toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as KinesisStartingPosition
    }

    @Test
    fun `supported positions preserve their values`() {
        KinesisStartingPosition.TrimHorizon shouldBeEqualTo KinesisStartingPosition.TrimHorizon
        KinesisStartingPosition.Latest shouldBeEqualTo KinesisStartingPosition.Latest
        KinesisStartingPosition.AtSequenceNumber("seq-at").sequenceNumber shouldBeEqualTo "seq-at"
        KinesisStartingPosition.AfterSequenceNumber("seq-after").sequenceNumber shouldBeEqualTo "seq-after"
        KinesisStartingPosition.AtTimestamp(
            Instant.parse("2026-08-27T00:00:00.123456789Z"),
        ).timestamp.nano shouldBeEqualTo
                123_456_789
    }

    @Test
    fun `sequence positions reject blank and control values`() {
        listOf("", " ", "seq\n1").forEach { value ->
            assertFailsWith<IllegalArgumentException> { KinesisStartingPosition.AtSequenceNumber(value) }
            assertFailsWith<IllegalArgumentException> { KinesisStartingPosition.AfterSequenceNumber(value) }
        }
    }

    @Test
    fun `positions survive serialization and singleton identity is preserved`() {
        (roundtrip(KinesisStartingPosition.TrimHorizon) === KinesisStartingPosition.TrimHorizon).shouldBeTrue()
        (roundtrip(KinesisStartingPosition.Latest) === KinesisStartingPosition.Latest).shouldBeTrue()
        roundtrip(KinesisStartingPosition.AtSequenceNumber("seq-at")) shouldBeEqualTo
                KinesisStartingPosition.AtSequenceNumber("seq-at")
        roundtrip(KinesisStartingPosition.AfterSequenceNumber("seq-after")) shouldBeEqualTo
                KinesisStartingPosition.AfterSequenceNumber("seq-after")
        roundtrip(KinesisStartingPosition.AtTimestamp(Instant.parse("2026-08-27T00:00:00.123456789Z"))) shouldBeEqualTo
                KinesisStartingPosition.AtTimestamp(Instant.parse("2026-08-27T00:00:00.123456789Z"))
    }
}
