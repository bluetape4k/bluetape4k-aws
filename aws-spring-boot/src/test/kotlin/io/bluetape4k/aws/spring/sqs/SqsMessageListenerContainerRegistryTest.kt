package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SqsMessageListenerContainerRegistryTest {

    @Test
    fun `registry rejects start while asynchronous stop is draining`() {
        val registry = SqsMessageListenerContainerRegistry()
        val container = mockk<SqsMessageListenerContainer>(relaxed = true)
        every { container.isAutoStartup } returns true
        every { container.phase } returns 0
        registry.register("orders", container)

        val stopCallback = slot<Runnable>()
        every { container.stop(capture(stopCallback)) } just Runs

        registry.start()
        registry.stop()

        val failure = assertThrows<IllegalStateException> { registry.start("orders") }
        failure.message shouldContain "listener is stopping"

        stopCallback.captured.run()
        registry.start("orders")

        verify(exactly = 2) { container.start() }
        registry.isRunning shouldBeEqualTo true
    }
}
