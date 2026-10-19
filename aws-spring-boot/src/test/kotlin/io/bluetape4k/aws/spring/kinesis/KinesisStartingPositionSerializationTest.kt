package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class KinesisStartingPositionSerializationTest {

    @Test
    fun `AtSequenceNumber Java 역직렬화도 빈 시퀀스 번호를 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            deserializeWithBlankSequenceNumber(KinesisStartingPosition.AtSequenceNumber("sequence"))
        }
    }

    @Test
    fun `AfterSequenceNumber Java 역직렬화도 빈 시퀀스 번호를 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            deserializeWithBlankSequenceNumber(KinesisStartingPosition.AfterSequenceNumber("sequence"))
        }
    }

    private fun deserializeWithBlankSequenceNumber(value: KinesisStartingPosition): KinesisStartingPosition {
        val sequenceNumberField = value.javaClass.getDeclaredField("sequenceNumber").apply {
            isAccessible = true
        }
        sequenceNumberField.set(value, " ")

        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(value) }
        }.toByteArray()

        return ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as KinesisStartingPosition
        }
    }
}
