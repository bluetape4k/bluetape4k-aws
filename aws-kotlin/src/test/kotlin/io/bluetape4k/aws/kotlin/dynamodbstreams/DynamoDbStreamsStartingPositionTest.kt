package io.bluetape4k.aws.kotlin.dynamodbstreams

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.io.serializer.BinarySerializers
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class DynamoDbStreamsStartingPositionTest {

    companion object: KLogging()

    @Suppress("DEPRECATION")
    private fun roundtripJdk(value: DynamoDbStreamsStartingPosition): DynamoDbStreamsStartingPosition {
        val bytes = BinarySerializers.Jdk.serialize(value)
        return BinarySerializers.Jdk.deserialize<DynamoDbStreamsStartingPosition>(bytes).shouldNotBeNull()
    }

    private fun roundtripFastFory(value: DynamoDbStreamsStartingPosition): DynamoDbStreamsStartingPosition {
        val bytes = BinarySerializers.FastFory.serialize(value)
        return BinarySerializers.FastFory.deserialize<DynamoDbStreamsStartingPosition>(bytes).shouldNotBeNull()
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
    fun `positions survive jdk serialization and singleton identity is preserved`() {
        roundtripJdk(DynamoDbStreamsStartingPosition.TrimHorizon) shouldBe DynamoDbStreamsStartingPosition.TrimHorizon
        roundtripJdk(DynamoDbStreamsStartingPosition.Latest) shouldBe DynamoDbStreamsStartingPosition.Latest

        roundtripJdk(DynamoDbStreamsStartingPosition.AtSequenceNumber("seq-at")) shouldBeEqualTo
                DynamoDbStreamsStartingPosition.AtSequenceNumber("seq-at")
        roundtripJdk(DynamoDbStreamsStartingPosition.AfterSequenceNumber("seq-after")) shouldBeEqualTo
                DynamoDbStreamsStartingPosition.AfterSequenceNumber("seq-after")
    }

    @Test
    fun `positions survive fastfory serialization and singleton identity is preserved`() {
        roundtripFastFory(DynamoDbStreamsStartingPosition.TrimHorizon) shouldBe DynamoDbStreamsStartingPosition.TrimHorizon
        roundtripFastFory(DynamoDbStreamsStartingPosition.Latest) shouldBe DynamoDbStreamsStartingPosition.Latest

        roundtripFastFory(DynamoDbStreamsStartingPosition.AtSequenceNumber("seq-at")) shouldBeEqualTo
                DynamoDbStreamsStartingPosition.AtSequenceNumber("seq-at")
        roundtripFastFory(DynamoDbStreamsStartingPosition.AfterSequenceNumber("seq-after")) shouldBeEqualTo
                DynamoDbStreamsStartingPosition.AfterSequenceNumber("seq-after")
    }
}
