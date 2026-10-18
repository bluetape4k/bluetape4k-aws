package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Duration

class SqsListenerFlowPoliciesTest {

    companion object: KLogging()

    @Test
    fun `listener exposes fixed admission and queue policy defaults`() {
        val listener = SqsProperties.Listener(maxMessages = 4, maxInFlight = 8)

        log.debug { "listener=$listener" }
        listener.backPressureMode shouldBeEqualTo SqsBackPressureMode.FIXED
        listener.maxInFlight shouldBeEqualTo 8
        listener.fifoBatchGroupingStrategy shouldBeEqualTo SqsFifoBatchGroupingStrategy.GROUP_BY_MESSAGE_GROUP_ID
        listener.queueAttributeNames shouldBeEqualTo emptySet()
        listener.queueNotFoundStrategy shouldBeEqualTo SqsQueueNotFoundStrategy.FAIL_FAST
    }

    @Test
    fun `listener default admission preserves configured polling concurrency`() {
        val listener = SqsProperties.Listener(maxMessages = 4, concurrency = 3)

        log.debug { "listener=$listener" }
        listener.maxInFlight shouldBeEqualTo 12
    }

    @Test
    fun `listener rejects invalid admission and attribute cache values`() {
        assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(maxInFlight = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SqsProperties.Listener(queueAttributeCacheTtl = Duration.ofSeconds(-1))
        }
    }
}
