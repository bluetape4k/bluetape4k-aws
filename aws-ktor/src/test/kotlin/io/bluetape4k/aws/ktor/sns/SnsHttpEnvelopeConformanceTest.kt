package io.bluetape4k.aws.ktor.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.aws.sns.SnsHttpEnvelopeConformanceFixtures
import io.bluetape4k.aws.sns.SnsHttpEnvelopeValidationException
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class SnsHttpEnvelopeConformanceTest {

    private val parser = SnsHttpMessageParser.default()

    @Test
    fun `공통 valid corpus를 같은 wire 결과로 변환한다`() {
        SnsHttpEnvelopeConformanceFixtures.validCases.forEach { case ->
            val message = parser.parse(case.json, case.messageTypeHeader)

            message.type.value shouldBeEqualTo case.expectedType.value
            message.messageId shouldBeEqualTo case.expectedMessageId
        }
    }

    @Test
    fun `공통 invalid corpus를 같은 reason으로 거부한다`() {
        SnsHttpEnvelopeConformanceFixtures.invalidCases.forEach { case ->
            val error = assertFailsWith<SnsHttpEnvelopeValidationException>(case.name) {
                parser.parse(case.json, case.messageTypeHeader)
            }

            error.reason shouldBeEqualTo case.expectedReason
            error.message.orEmpty() shouldBeEqualTo case.expectedMessage
            error.message.orEmpty().contains("signature-secret").shouldBeFalse()
        }
    }

    @Test
    fun `주입한 mapper 구성에서도 duplicate detection을 유지한다`() {
        val duplicate = SnsHttpEnvelopeConformanceFixtures.invalidCases.single { it.name == "duplicate-type" }
        val customParser = SnsHttpMessageParser(ObjectMapper())

        val error = assertFailsWith<SnsHttpEnvelopeValidationException> {
            customParser.parse(duplicate.json, duplicate.messageTypeHeader)
        }

        error.reason shouldBeEqualTo duplicate.expectedReason
        error.message.orEmpty() shouldBeEqualTo duplicate.expectedMessage
    }
}
