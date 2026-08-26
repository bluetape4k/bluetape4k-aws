package io.bluetape4k.aws.kotlin.dynamodbstreams

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.jupiter.api.Test

class DynamoDbStreamsStartingPositionTest {

    private fun roundtrip(value: DynamoDbStreamsStartingPosition): DynamoDbStreamsStartingPosition {
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(value) }
        }.toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
            as DynamoDbStreamsStartingPosition
    }

    @Test
    fun `all supported positions preserve their sequence contract`() {
        DynamoDbStreamsStartingPosition.TrimHorizon shouldBeEqualTo DynamoDbStreamsStartingPosition.TrimHorizon
        DynamoDbStreamsStartingPosition.Latest shouldBeEqualTo DynamoDbStreamsStartingPosition.Latest
        DynamoDbStreamsStartingPosition.AtSequenceNumber("seq-1").sequenceNumber shouldBeEqualTo "seq-1"
        DynamoDbStreamsStartingPosition.AfterSequenceNumber("seq-1").sequenceNumber shouldBeEqualTo "seq-1"
    }

    @Test
    fun `sequence based positions reject blank values`() {
        assertFailsWith<IllegalArgumentException> {
            DynamoDbStreamsStartingPosition.AtSequenceNumber(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbStreamsStartingPosition.AfterSequenceNumber("")
        }
    }

    @Test
    fun `positions survive serialization and singleton identity is preserved`() {
        (roundtrip(DynamoDbStreamsStartingPosition.TrimHorizon) === DynamoDbStreamsStartingPosition.TrimHorizon)
            .shouldBeTrue()
        (roundtrip(DynamoDbStreamsStartingPosition.Latest) === DynamoDbStreamsStartingPosition.Latest)
            .shouldBeTrue()
        roundtrip(DynamoDbStreamsStartingPosition.AtSequenceNumber("seq-at")) shouldBeEqualTo
                DynamoDbStreamsStartingPosition.AtSequenceNumber("seq-at")
        roundtrip(DynamoDbStreamsStartingPosition.AfterSequenceNumber("seq-after")) shouldBeEqualTo
                DynamoDbStreamsStartingPosition.AfterSequenceNumber("seq-after")
    }
}
