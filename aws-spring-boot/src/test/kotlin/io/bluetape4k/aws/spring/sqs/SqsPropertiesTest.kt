package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

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
        val invalidInterval = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(
                messageVisibilityHeartbeatIntervalSeconds = 0,
                messageVisibilityHeartbeatSeconds = 30,
            )
        }
        invalidInterval.message shouldContain "messageVisibilityHeartbeatIntervalSeconds"

        val invalidHeartbeat = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(
                messageVisibilityHeartbeatIntervalSeconds = 30,
                messageVisibilityHeartbeatSeconds = 43_201,
            )
        }
        invalidHeartbeat.message shouldContain "messageVisibilityHeartbeatSeconds"

        val equalValues = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(
                messageVisibilityHeartbeatIntervalSeconds = 30,
                messageVisibilityHeartbeatSeconds = 30,
            )
        }
        equalValues.message shouldContain "must be less than"
    }

    @Test
    fun `simple SQS validation uses bluetape helpers for ranges and lower bounds`() {
        val maxMessages = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(maxMessages = 0)
        }
        maxMessages.message shouldContain "maxMessages"

        val waitTimeSeconds = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(waitTimeSeconds = 21)
        }
        waitTimeSeconds.message shouldContain "waitTimeSeconds"

        val visibilityTimeoutSeconds = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(visibilityTimeoutSeconds = 43_201)
        }
        visibilityTimeoutSeconds.message shouldContain "visibilityTimeoutSeconds"

        val errorVisibilityTimeoutSeconds = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(errorVisibilityTimeoutSeconds = 43_201)
        }
        errorVisibilityTimeoutSeconds.message shouldContain "errorVisibilityTimeoutSeconds"

        val concurrency = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(concurrency = 0)
        }
        concurrency.message shouldContain "concurrency"

        val stopTimeoutMillis = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(stopTimeoutMillis = 0)
        }
        stopTimeoutMillis.message shouldContain "stopTimeoutMillis"

        val maxAttempts = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Retry(maxAttempts = 0)
        }
        maxAttempts.message shouldContain "maxAttempts"

        val initialBackoff = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Retry(initialBackoff = Duration.ofMillis(-1))
        }
        initialBackoff.message shouldContain "initialBackoff"

        val maxBackoff = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Retry(maxBackoff = Duration.ofMillis(-1))
        }
        maxBackoff.message shouldContain "maxBackoff"

        val multiplier = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Retry(multiplier = 0.99)
        }
        multiplier.message shouldContain "multiplier"

        val jitterRatio = assertFailsWith<IllegalArgumentException> {
            SqsProperties.Retry(jitterRatio = 1.01)
        }
        jitterRatio.message shouldContain "jitterRatio"

        val deadLetterTargetArn = assertFailsWith<IllegalArgumentException> {
            SqsProperties.RedrivePolicy(deadLetterTargetArn = " ", maxReceiveCount = 1)
        }
        deadLetterTargetArn.message shouldContain "deadLetterTargetArn"

        val maxReceiveCount = assertFailsWith<IllegalArgumentException> {
            SqsProperties.RedrivePolicy(deadLetterTargetArn = "arn:aws:sqs:region:account:dead", maxReceiveCount = 0)
        }
        maxReceiveCount.message shouldContain "maxReceiveCount"
    }

    @Test
    fun `simple SQS validation accepts bluetape helper boundaries`() {
        SqsProperties.Listener(
            maxMessages = 1,
            waitTimeSeconds = 0,
            visibilityTimeoutSeconds = 0,
            errorVisibilityTimeoutSeconds = 43_200,
            concurrency = 1,
            stopTimeoutMillis = 1,
        )
        SqsProperties.Listener(maxMessages = 10, waitTimeSeconds = 20)

        SqsProperties.Retry(
            maxAttempts = 1,
            initialBackoff = Duration.ZERO,
            maxBackoff = Duration.ZERO,
            multiplier = 1.0,
            jitterRatio = 0.0,
        )
        SqsProperties.Retry(jitterRatio = 1.0)

        SqsProperties.RedrivePolicy(
            deadLetterTargetArn = "arn:aws:sqs:region:account:dead",
            maxReceiveCount = 1,
        )
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
