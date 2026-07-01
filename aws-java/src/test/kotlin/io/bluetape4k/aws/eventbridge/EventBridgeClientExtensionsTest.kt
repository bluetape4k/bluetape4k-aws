package io.bluetape4k.aws.eventbridge

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.eventbridge.model.putEventsRequestEntryOf
import io.bluetape4k.aws.eventbridge.model.targetOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse
import software.amazon.awssdk.services.eventbridge.model.PutTargetsRequest
import software.amazon.awssdk.services.eventbridge.model.PutTargetsResponse
import software.amazon.awssdk.services.eventbridge.model.RemoveTargetsRequest
import software.amazon.awssdk.services.eventbridge.model.RemoveTargetsResponse

class EventBridgeClientExtensionsTest {

    private val client = mockk<EventBridgeClient>()

    @Test
    fun `putEvents delegates once and preserves raw partial failure response`() {
        val entry = putEventsRequestEntryOf("source", "type", "{}")
        val expected = PutEventsResponse.builder().failedEntryCount(1).build()
        every { client.putEvents(any<PutEventsRequest>()) } returns expected

        val result = client.putEvents(listOf(entry))

        result shouldBeSameInstanceAs expected
        result.failedEntryCount() shouldBeEqualTo 1
        verify(exactly = 1) { client.putEvents(any<PutEventsRequest>()) }
    }

    @Test
    fun `putTargets delegates once and preserves raw partial failure response`() {
        val target = targetOf("target", "arn:aws:lambda:us-east-1:123456789012:function:test")
        val expected = PutTargetsResponse.builder().failedEntryCount(1).build()
        every { client.putTargets(any<PutTargetsRequest>()) } returns expected

        val result = client.putTargets("rule", listOf(target))

        result shouldBeSameInstanceAs expected
        result.failedEntryCount() shouldBeEqualTo 1
        verify(exactly = 1) { client.putTargets(any<PutTargetsRequest>()) }
    }

    @Test
    fun `removeTargets delegates once and preserves raw partial failure response`() {
        val expected = RemoveTargetsResponse.builder().failedEntryCount(1).build()
        every { client.removeTargets(any<RemoveTargetsRequest>()) } returns expected

        val result = client.removeTargets("rule", listOf("target"))

        result shouldBeSameInstanceAs expected
        result.failedEntryCount() shouldBeEqualTo 1
        verify(exactly = 1) { client.removeTargets(any<RemoveTargetsRequest>()) }
    }
}
