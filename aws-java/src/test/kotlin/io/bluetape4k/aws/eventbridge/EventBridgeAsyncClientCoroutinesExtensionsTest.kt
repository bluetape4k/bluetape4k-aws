package io.bluetape4k.aws.eventbridge

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.eventbridge.model.putEventsRequestEntryOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

class EventBridgeAsyncClientCoroutinesExtensionsTest {

    private val client = mockk<EventBridgeAsyncClient>()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `putEvents coroutine awaits raw SDK response once`() = runTest {
        val entry = putEventsRequestEntryOf("source", "type", "{}")
        val expected = PutEventsResponse.builder().failedEntryCount(1).build()
        every { client.putEvents(any<PutEventsRequest>()) } returns CompletableFuture.completedFuture(expected)

        val result = client.putEvents(listOf(entry))

        result shouldBeSameInstanceAs expected
        result.failedEntryCount() shouldBeEqualTo 1
        verify(exactly = 1) { client.putEvents(any<PutEventsRequest>()) }
    }

    @Test
    fun `putEvents coroutine propagates cancellation`() = runTest {
        val entry = putEventsRequestEntryOf("source", "type", "{}")
        val future = CompletableFuture<PutEventsResponse>()
        future.completeExceptionally(CancellationException("cancelled"))
        every { client.putEvents(any<PutEventsRequest>()) } returns future

        assertFailsWith<CancellationException> {
            client.putEvents(listOf(entry))
        }
    }

    @Test
    fun `list helpers validate limits before async AWS call`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            client.listRulesAsync(limit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            client.listTargetsByRuleAsync("rule", limit = 101)
        }
        assertFailsWith<IllegalArgumentException> {
            client.listRules(limit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            client.listTargetsByRule("rule", limit = 101)
        }
    }
}
