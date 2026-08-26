package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration

class SqsListenerFlowPoliciesTest {

    @Test
    fun `listener exposes fixed admission and queue policy defaults`() {
        val listener = SqsProperties.Listener(maxMessages = 4, maxInFlight = 8)

        listener.backPressureMode shouldBeEqualTo SqsBackPressureMode.FIXED
        listener.maxInFlight shouldBeEqualTo 8
        listener.fifoBatchGroupingStrategy shouldBeEqualTo
            SqsFifoBatchGroupingStrategy.GROUP_BY_MESSAGE_GROUP_ID
        listener.queueAttributeNames shouldBeEqualTo emptySet<QueueAttributeName>()
        listener.queueNotFoundStrategy shouldBeEqualTo SqsQueueNotFoundStrategy.FAIL_FAST
    }

    @Test
    fun `listener default admission preserves configured polling concurrency`() {
        val listener = SqsProperties.Listener(maxMessages = 4, concurrency = 3)

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
