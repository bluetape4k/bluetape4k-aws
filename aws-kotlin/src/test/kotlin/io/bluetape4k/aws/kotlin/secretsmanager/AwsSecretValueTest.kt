package io.bluetape4k.aws.kotlin.secretsmanager

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsSecretValueTest {

    @Test
    fun `secret value redacts diagnostic output`() {
        val secret = awsSecretValueOf(SENTINEL)

        secret.reveal() shouldBeEqualTo SENTINEL
        secret.toString() shouldBeEqualTo AwsSecretValue.REDACTED
        secret.hashCode() shouldBeEqualTo AwsSecretValue.REDACTED.hashCode()
    }

    @Test
    fun `secret value uses constant time equality without exposing raw value`() {
        val secret = AwsSecretValue.of(SENTINEL)

        (secret == AwsSecretValue(SENTINEL)).shouldBeEqualTo(true)
        (secret == AwsSecretValue("other-value")).shouldBeEqualTo(false)
        secret.toString().contains(SENTINEL).shouldBeFalse()
    }

    @Test
    fun `secret value rejects blank input without leaking sentinel`() {
        val error = assertFailsWith<IllegalArgumentException> {
            awsSecretValueOf(" \t")
        }

        error.message.orEmpty().contains(SENTINEL).shouldBeFalse()
    }

    private companion object {
        private const val SENTINEL = "raw-secret-value"
    }
}
