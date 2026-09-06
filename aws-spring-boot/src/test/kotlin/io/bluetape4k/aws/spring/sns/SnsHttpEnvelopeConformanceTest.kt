package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.aws.sns.SnsHttpEnvelopeConformanceFixtures
import io.bluetape4k.aws.sns.SnsHttpEnvelopeValidationException
import org.junit.jupiter.api.Test

class SnsHttpEnvelopeConformanceTest {

    @Test
    fun `공통 valid corpus를 같은 wire 결과로 변환한다`() {
        SnsHttpEnvelopeConformanceFixtures.validCases.forEach { case ->
            val message = SnsHttpMessageParser.parse(case.json, case.messageTypeHeader)

            message.type.value shouldBeEqualTo case.expectedType.value
            message.messageId shouldBeEqualTo case.expectedMessageId
        }
    }

    @Test
    fun `공통 invalid corpus를 같은 reason으로 거부한다`() {
        SnsHttpEnvelopeConformanceFixtures.invalidCases.forEach { case ->
            val error = assertFailsWith<SnsHttpEnvelopeValidationException>(case.name) {
                SnsHttpMessageParser.parse(case.json, case.messageTypeHeader)
            }

            error.reason shouldBeEqualTo case.expectedReason
            error.message.orEmpty() shouldBeEqualTo case.expectedMessage
            error.message.orEmpty().contains("signature-secret").shouldBeFalse()
        }
    }
}
