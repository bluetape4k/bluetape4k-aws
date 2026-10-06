package io.bluetape4k.aws.kotlin.ses.model

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import org.junit.jupiter.api.Test

class SesModelSupportTest {

    companion object: KLogging()

    @Test
    fun `destinationOf는 to address를 설정한다`() {
        val destination = destinationOf("a@example.com", "b@example.com")

        log.debug { "destination=$destination" }
        destination.toAddresses shouldBeEqualTo listOf("a@example.com", "b@example.com")
    }

    @Test
    fun `contentOf는 data와 charset을 설정한다`() {
        val content = contentOf("hello")

        log.debug { "content=$content" }
        content.data shouldBeEqualTo "hello"
        content.charset shouldBeEqualTo "UTF-8"
    }

    @Test
    fun `rawMessageOf는 data를 설정한다`() {
        val bytes = "hello".toUtf8Bytes()
        val raw = rawMessageOf(bytes)

        log.debug { "raw=$raw" }
        raw.data.toUtf8String() shouldBeEqualTo "hello"
    }

    @Test
    fun `sendEmailRequestOf는 source destination message를 설정한다`() {
        val request = sendEmailRequestOf(
            source = "from@example.com",
            destination = destinationOf("to@example.com"),
            message = messageOf(
                subject = contentOf("subject"),
                body = textBodyOf(contentOf("body")),
            ),
        )

        log.debug { "request=$request" }
        request.source shouldBeEqualTo "from@example.com"
        request.destination?.toAddresses shouldBeEqualTo listOf("to@example.com")
        request.message?.subject?.data shouldBeEqualTo "subject"
    }

    @Test
    fun `destinationOf는 빈 to address를 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            destinationOf()
        }
    }

    @Test
    fun `contentOf는 빈 data를 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            contentOf("")
        }

        assertFailsWith<IllegalArgumentException> {
            contentOf("     ")
        }
    }

    @Test
    fun `rawMessageOf는 빈 data를 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            rawMessageOf(byteArrayOf())
        }
    }
}
