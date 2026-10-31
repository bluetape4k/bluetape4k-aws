package io.bluetape4k.aws.dynamodbstreams

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.io.serializer.BinarySerializers
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class DynamoDbStreamsStartingPositionTest {

    companion object: KLogging()

    private fun roundtrip(value: DynamoDbStreamsStartingPosition): DynamoDbStreamsStartingPosition {
        val bytes = BinarySerializers.JdkUnfiltered.serialize(value)
        return BinarySerializers.JdkUnfiltered.deserialize<DynamoDbStreamsStartingPosition>(bytes).shouldNotBeNull()
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
        roundtrip(DynamoDbStreamsStartingPosition.TrimHorizon) shouldBe DynamoDbStreamsStartingPosition.TrimHorizon
        roundtrip(DynamoDbStreamsStartingPosition.Latest) shouldBe DynamoDbStreamsStartingPosition.Latest
        roundtrip(DynamoDbStreamsStartingPosition.AtSequenceNumber("seq-at")) shouldBeEqualTo
                DynamoDbStreamsStartingPosition.AtSequenceNumber("seq-at")
        roundtrip(DynamoDbStreamsStartingPosition.AfterSequenceNumber("seq-after")) shouldBeEqualTo
                DynamoDbStreamsStartingPosition.AfterSequenceNumber("seq-after")
    }
}
