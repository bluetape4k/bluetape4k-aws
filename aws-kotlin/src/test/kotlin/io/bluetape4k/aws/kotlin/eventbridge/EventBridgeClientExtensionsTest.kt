package io.bluetape4k.aws.kotlin.eventbridge

import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequest
import aws.sdk.kotlin.services.eventbridge.model.PutEventsResponse
import aws.sdk.kotlin.services.eventbridge.model.PutTargetsRequest
import aws.sdk.kotlin.services.eventbridge.model.PutTargetsResponse
import aws.sdk.kotlin.services.eventbridge.model.RemoveTargetsRequest
import aws.sdk.kotlin.services.eventbridge.model.RemoveTargetsResponse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.kotlin.eventbridge.model.putEventsRequestEntryOf
import io.bluetape4k.aws.kotlin.eventbridge.model.targetOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EventBridgeClientExtensionsTest {

    private val client = mockk<EventBridgeClient>()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `putEvents delegates once and preserves raw partial failure response`() = runTest {
        val entry = putEventsRequestEntryOf("source", "type", "{}")
        val expected = PutEventsResponse {
            failedEntryCount = 1
        }
        coEvery { client.putEvents(any<PutEventsRequest>()) } returns expected

        val result = client.putEvents(listOf(entry))

        result shouldBeSameInstanceAs expected
        result.failedEntryCount shouldBeEqualTo 1
        coVerify(exactly = 1) { client.putEvents(any<PutEventsRequest>()) }
    }

    @Test
    fun `putTargets delegates once and preserves raw partial failure response`() = runTest {
        val target = targetOf("target", "arn:aws:lambda:us-east-1:123456789012:function:test")
        val expected = PutTargetsResponse {
            failedEntryCount = 1
        }
        coEvery { client.putTargets(any<PutTargetsRequest>()) } returns expected

        val result = client.putTargets("rule", listOf(target))

        result shouldBeSameInstanceAs expected
        result.failedEntryCount shouldBeEqualTo 1
        coVerify(exactly = 1) { client.putTargets(any<PutTargetsRequest>()) }
    }

    @Test
    fun `removeTargets delegates once and preserves raw partial failure response`() = runTest {
        val expected = RemoveTargetsResponse {
            failedEntryCount = 1
        }
        coEvery { client.removeTargets(any<RemoveTargetsRequest>()) } returns expected

        val result = client.removeTargets("rule", listOf("target"))

        result shouldBeSameInstanceAs expected
        result.failedEntryCount shouldBeEqualTo 1
        coVerify(exactly = 1) { client.removeTargets(any<RemoveTargetsRequest>()) }
    }
}
