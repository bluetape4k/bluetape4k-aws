package io.bluetape4k.aws.spring.eventbridge

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.eventbridge.model.putEventsRequestEntryOf
import io.bluetape4k.aws.eventbridge.model.targetOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse
import software.amazon.awssdk.services.eventbridge.model.PutTargetsRequest
import software.amazon.awssdk.services.eventbridge.model.PutTargetsResponse
import software.amazon.awssdk.services.eventbridge.model.RemoveTargetsRequest
import software.amazon.awssdk.services.eventbridge.model.RemoveTargetsResponse
import java.util.concurrent.CompletableFuture

class EventBridgeCoroutinesTemplateTest {

    @Test
    fun `putEvents returns raw SDK partial failure response`() = runTest {
        val client = mockk<EventBridgeAsyncClient>()
        val request = slot<PutEventsRequest>()
        val response = PutEventsResponse.builder().failedEntryCount(1).build()
        val entry = putEventsRequestEntryOf("orders", "order.created", "{}")

        every { client.putEvents(capture(request)) } returns CompletableFuture.completedFuture(response)

        val result = template(client).putEvents(listOf(entry))

        result shouldBeSameInstanceAs response
        result.failedEntryCount() shouldBeEqualTo 1
        request.captured.entries().size shouldBeEqualTo 1
        verify(exactly = 1) { client.putEvents(any<PutEventsRequest>()) }
    }

    @Test
    fun `putTargets applies configured default event bus when omitted`() = runTest {
        val client = mockk<EventBridgeAsyncClient>()
        val request = slot<PutTargetsRequest>()
        val response = PutTargetsResponse.builder().failedEntryCount(1).build()
        val target = targetOf("lambda", "arn:aws:lambda:us-east-1:123456789012:function:handler")

        every { client.putTargets(capture(request)) } returns CompletableFuture.completedFuture(response)

        val result = template(
            client,
            EventBridgeProperties(region = "us-east-1", defaultEventBusName = "orders"),
        ).putTargets("order-rule", listOf(target))

        result shouldBeSameInstanceAs response
        result.failedEntryCount() shouldBeEqualTo 1
        request.captured.rule() shouldBeEqualTo "order-rule"
        request.captured.eventBusName() shouldBeEqualTo "orders"
        verify(exactly = 1) { client.putTargets(any<PutTargetsRequest>()) }
    }

    @Test
    fun `removeTargets returns raw SDK partial failure response`() = runTest {
        val client = mockk<EventBridgeAsyncClient>()
        val request = slot<RemoveTargetsRequest>()
        val response = RemoveTargetsResponse.builder().failedEntryCount(1).build()

        every { client.removeTargets(capture(request)) } returns CompletableFuture.completedFuture(response)

        val result = template(client).removeTargets("order-rule", listOf("lambda"), eventBusName = "orders")

        result shouldBeSameInstanceAs response
        result.failedEntryCount() shouldBeEqualTo 1
        request.captured.rule() shouldBeEqualTo "order-rule"
        request.captured.eventBusName() shouldBeEqualTo "orders"
        verify(exactly = 1) { client.removeTargets(any<RemoveTargetsRequest>()) }
    }

    private fun template(
        client: EventBridgeAsyncClient,
        properties: EventBridgeProperties = EventBridgeProperties(region = "us-east-1"),
    ): EventBridgeCoroutinesTemplate =
        EventBridgeCoroutinesTemplate(client, properties)
}
