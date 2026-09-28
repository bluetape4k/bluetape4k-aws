package io.bluetape4k.aws.secretsmanager

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsSecretValueTest {

    private companion object: KLogging() {
        private const val SENTINEL = "raw-secret-value"
    }

    @Test
    fun `secret value redacts diagnostic output`() {
        val secret = awsSecretValueOf(SENTINEL)

        log.debug { "secret=$secret" }
        secret.reveal() shouldBeEqualTo SENTINEL
        secret.toString() shouldBeEqualTo AwsSecretValue.REDACTED
        secret.hashCode() shouldBeEqualTo AwsSecretValue.REDACTED.hashCode()
    }

    @Test
    fun `secret value uses constant time equality without exposing raw value`() {
        val secret = AwsSecretValue.of(SENTINEL)

        log.debug { "secret=$secret" }

        secret shouldBeEqualTo AwsSecretValue(SENTINEL)
        secret shouldNotBeEqualTo AwsSecretValue("other-value")
        secret.toString() shouldNotContain SENTINEL
    }

    @Test
    fun `secret value rejects blank input without leaking sentinel`() {
        val error = assertFailsWith<IllegalArgumentException> {
            awsSecretValueOf(" \t")
        }

        error.message shouldNotContain SENTINEL
    }
}
