package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test

class SqsPropertiesTest {

    @Test
    fun `heartbeat requires interval and timeout together`() {
        val intervalOnly = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(messageVisibilityHeartbeatIntervalSeconds = 5)
        }
        intervalOnly.message shouldContain "must be configured together"

        val timeoutOnly = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(messageVisibilityHeartbeatSeconds = 30)
        }
        timeoutOnly.message shouldContain "must be configured together"
    }

    @Test
    fun `heartbeat validates positive SQS range and interval ordering`() {
        assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(
                messageVisibilityHeartbeatIntervalSeconds = 0,
                messageVisibilityHeartbeatSeconds = 30,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(
                messageVisibilityHeartbeatIntervalSeconds = 30,
                messageVisibilityHeartbeatSeconds = 43_201,
            )
        }
        val equalValues = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(
                messageVisibilityHeartbeatIntervalSeconds = 30,
                messageVisibilityHeartbeatSeconds = 30,
            )
        }
        equalValues.message shouldContain "must be less than"
    }

    @Test
    fun `valid heartbeat values bind both settings`() {
        val listener = SqsProperties.Listener(
            messageVisibilityHeartbeatIntervalSeconds = 10,
            messageVisibilityHeartbeatSeconds = 30,
        )

        listener.messageVisibilityHeartbeatIntervalSeconds shouldBeEqualTo 10
        listener.messageVisibilityHeartbeatSeconds shouldBeEqualTo 30
    }
}
