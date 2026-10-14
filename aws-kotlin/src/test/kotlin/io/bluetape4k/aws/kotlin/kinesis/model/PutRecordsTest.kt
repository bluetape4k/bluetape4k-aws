package io.bluetape4k.aws.kotlin.kinesis.model

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class PutRecordsTest {

    companion object: KLogging()

    @Test
    fun `putRecordsRequestEntryOf는 partitionKey와 data로 entry를 생성한다`() {
        val data = "hello".toByteArray()
        val entry = putRecordsRequestEntryOf(partitionKey = "pk-001", data = data)

        log.debug { "entry=$entry" }
        entry.partitionKey shouldBeEqualTo "pk-001"
        entry.data shouldBeEqualTo data
    }

    @Test
    fun `putRecordsRequestEntryOf는 builder 블록으로 추가 설정이 가능하다`() {
        val entry = putRecordsRequestEntryOf(
            partitionKey = "pk-002",
            data = "world".toByteArray()
        ) {
            explicitHashKey = "123456789012345678901234567890"
        }

        log.debug { "entry=$entry" }
        entry.shouldNotBeNull()
        entry.explicitHashKey shouldBeEqualTo "123456789012345678901234567890"
    }

    @Test
    fun `putRecordsRequestEntryOf는 빈 partitionKey를 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            putRecordsRequestEntryOf(partitionKey = "", data = ByteArray(0))
        }
    }

    @Test
    fun `putRecordsRequestEntryOf는 공백만 있는 partitionKey를 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            putRecordsRequestEntryOf(partitionKey = "  ", data = ByteArray(0))
        }
    }

    @Test
    fun `여러 entries를 목록으로 생성할 수 있다`() {
        val entries = listOf(
            putRecordsRequestEntryOf("pk-1", "data-1".toByteArray()),
            putRecordsRequestEntryOf("pk-2", "data-2".toByteArray()),
            putRecordsRequestEntryOf("pk-3", "data-3".toByteArray()),
        )

        entries shouldHaveSize 3
        entries[0].partitionKey shouldBeEqualTo "pk-1"
        entries[1].partitionKey shouldBeEqualTo "pk-2"
        entries[2].partitionKey shouldBeEqualTo "pk-3"
    }
}
