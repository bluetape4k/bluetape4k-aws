package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class SqsQueueAttributesResolverTest {

    @Test
    fun `successful queue attributes are cached by URL and requested names`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val now = AtomicLong(0)
        val names = setOf(QueueAttributeName.FIFO_QUEUE)
        coEvery { operations.getQueueAttributes(QUEUE_URL, names) } returns
            mapOf(QueueAttributeName.FIFO_QUEUE to "true")
        val resolver = DefaultSqsQueueAttributesResolver(
            operations = operations,
            cacheTtl = Duration.ofSeconds(10),
            nanoTime = now::get,
        )

        resolver.resolve(QUEUE_URL, names).isFifo shouldBeEqualTo true
        resolver.resolve(QUEUE_URL, names).values shouldBeEqualTo
            mapOf(QueueAttributeName.FIFO_QUEUE to "true")

        coVerify(exactly = 1) { operations.getQueueAttributes(QUEUE_URL, names) }
    }

    @Test
    fun `expired queue attributes are looked up again`() = runTest {
        val operations = mockk<SqsOperations>()
        val now = AtomicLong(0)
        val names = setOf(QueueAttributeName.VISIBILITY_TIMEOUT)
        coEvery { operations.getQueueAttributes(QUEUE_URL, names) } returns
            mapOf(QueueAttributeName.VISIBILITY_TIMEOUT to "30")
        val resolver = DefaultSqsQueueAttributesResolver(
            operations = operations,
            cacheTtl = Duration.ofNanos(10),
            nanoTime = now::get,
        )

        resolver.resolve(QUEUE_URL, names)
        now.set(10)
        resolver.resolve(QUEUE_URL, names)

        coVerify(exactly = 2) { operations.getQueueAttributes(QUEUE_URL, names) }
    }

    companion object {
        private const val QUEUE_URL = "https://sqs.local/orders.fifo"
    }
}
