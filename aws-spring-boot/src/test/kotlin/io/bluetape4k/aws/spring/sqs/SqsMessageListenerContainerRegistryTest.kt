package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test

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

        val failure = assertFailsWith<IllegalStateException> { registry.start("orders") }
        failure.message shouldContain "listener is stopping"

        stopCallback.captured.run()
        registry.start("orders")

        verify(exactly = 2) { container.start() }
        registry.isRunning shouldBeEqualTo true
    }

    @Test
    fun individualStopKeepsRegistryRunningAndGlobalStopDrainsRemainingListeners() {
        val registry = SqsMessageListenerContainerRegistry()
        val orders = mockk<SqsMessageListenerContainer>(relaxed = true)
        val payments = mockk<SqsMessageListenerContainer>(relaxed = true)
        val inventory = mockk<SqsMessageListenerContainer>(relaxed = true)
        listOf(orders, payments, inventory).forEach {
            every { it.isAutoStartup } returns true
            every { it.phase } returns 0
        }
        registry.register("orders", orders)
        registry.register("payments", payments)

        val orderStopCallbacks = mutableListOf<Runnable>()
        val paymentStopCallbacks = mutableListOf<Runnable>()
        val inventoryStopCallbacks = mutableListOf<Runnable>()
        every { orders.stop(any()) } answers {
            orderStopCallbacks += firstArg<Runnable>()
        }
        every { payments.stop(any()) } answers {
            paymentStopCallbacks += firstArg<Runnable>()
        }
        every { inventory.stop(any()) } answers {
            inventoryStopCallbacks += firstArg<Runnable>()
        }

        registry.start()
        registry.isRunning.shouldBeTrue()

        var individualCallbackCount = 0
        registry.stop("orders") { individualCallbackCount++ }
        orderStopCallbacks.single().run()
        individualCallbackCount shouldBeEqualTo 1
        registry.isRunning.shouldBeTrue()

        registry.register("inventory", inventory)
        verify(exactly = 1) { inventory.start() }
        registry.isRunning.shouldBeTrue()

        var globalCallbackCount = 0
        registry.stop { globalCallbackCount++ }
        verify(exactly = 1) { payments.stop(any()) }
        verify(exactly = 1) { inventory.stop(any()) }
        globalCallbackCount shouldBeEqualTo 0

        paymentStopCallbacks.single().run()
        inventoryStopCallbacks.single().run()
        orderStopCallbacks[1].run()

        globalCallbackCount shouldBeEqualTo 1
        registry.isRunning shouldBeEqualTo false
    }
}
