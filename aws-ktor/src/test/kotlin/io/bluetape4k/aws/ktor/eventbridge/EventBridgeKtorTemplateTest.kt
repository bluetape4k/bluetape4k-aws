package io.bluetape4k.aws.ktor.eventbridge

import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.model.ListRulesRequest
import aws.sdk.kotlin.services.eventbridge.model.ListRulesResponse
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequest
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequestEntry
import aws.sdk.kotlin.services.eventbridge.model.PutEventsResponse
import aws.sdk.kotlin.services.eventbridge.model.PutEventsResultEntry
import aws.sdk.kotlin.services.eventbridge.model.PutTargetsRequest
import aws.sdk.kotlin.services.eventbridge.model.PutTargetsResponse
import aws.sdk.kotlin.services.eventbridge.model.RemoveTargetsRequest
import aws.sdk.kotlin.services.eventbridge.model.RemoveTargetsResponse
import aws.sdk.kotlin.services.eventbridge.model.Rule
import aws.sdk.kotlin.services.eventbridge.model.RuleState
import aws.sdk.kotlin.services.eventbridge.model.Target
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test

class EventBridgeKtorTemplateTest {

    @Test
    fun `putEvents preserves ordered partial failure details`() = runSuspendIO {
        val client = mockk<EventBridgeClient>()
        val request = slot<PutEventsRequest>()
        val response = PutEventsResponse {
            failedEntryCount = 1
            entries = listOf(
                PutEventsResultEntry { eventId = "event-1" },
                PutEventsResultEntry {
                    errorCode = "MalformedDetail"
                    errorMessage = "detail must be valid JSON"
                },
            )
        }
        val entries = listOf(
            PutEventsRequestEntry {
                eventBusName = "orders"
                detailType = "order.created"
                detail = "{}"
            },
            PutEventsRequestEntry {
                eventBusName = "orders"
                detailType = "order.updated"
                detail = "not-json"
            },
        )

        coEvery { client.putEvents(capture(request)) } returns response

        val result = EventBridgeKtorTemplate(client).putEvents(entries)

        result shouldBeSameInstanceAs response
        result.failedEntryCount shouldBeEqualTo 1
        result.entries?.map { it.eventId ?: it.errorCode } shouldBeEqualTo listOf("event-1", "MalformedDetail")
        result.entries?.get(1)?.errorMessage shouldBeEqualTo "detail must be valid JSON"
        request.captured.entries?.map { it.detailType } shouldBeEqualTo listOf("order.created", "order.updated")
        coVerify(exactly = 1) { client.putEvents(any<PutEventsRequest>()) }
    }

    @Test
    fun `putTargets applies default event bus and retains optional target fields`() = runSuspendIO {
        val client = mockk<EventBridgeClient>()
        val request = slot<PutTargetsRequest>()
        val response = PutTargetsResponse { failedEntryCount = 1 }
        val target = Target {
            id = "lambda"
            arn = "arn:aws:lambda:us-east-1:123456789012:function:handler"
            roleArn = "arn:aws:iam::123456789012:role/events"
            inputPath = "$.detail"
        }

        coEvery { client.putTargets(capture(request)) } returns response

        val result = EventBridgeKtorTemplate(client, defaultEventBusName = "orders")
            .putTargets("order-rule", listOf(target))

        result.failedEntryCount shouldBeEqualTo 1
        request.captured.eventBusName shouldBeEqualTo "orders"
        request.captured.targets?.single()?.roleArn shouldBeEqualTo target.roleArn
        request.captured.targets?.single()?.inputPath shouldBeEqualTo target.inputPath
    }

    @Test
    fun `removeTargets preserves raw partial failure response`() = runSuspendIO {
        val client = mockk<EventBridgeClient>()
        val request = slot<RemoveTargetsRequest>()
        val response = RemoveTargetsResponse { failedEntryCount = 1 }

        coEvery { client.removeTargets(capture(request)) } returns response

        val result = EventBridgeKtorTemplate(client)
            .removeTargets("order-rule", listOf("lambda"), eventBusName = "orders")

        result shouldBeSameInstanceAs response
        result.failedEntryCount shouldBeEqualTo 1
        request.captured.rule shouldBeEqualTo "order-rule"
        request.captured.eventBusName shouldBeEqualTo "orders"
        coVerify(exactly = 1) { client.removeTargets(any<RemoveTargetsRequest>()) }
    }

    @Test
    fun `listRules preserves pagination token and unknown rule state`() = runSuspendIO {
        val client = mockk<EventBridgeClient>()
        val request = slot<ListRulesRequest>()
        val response = ListRulesResponse {
            nextToken = "next-page"
            rules = listOf(Rule { name = "future-rule"; state = RuleState.SdkUnknown("PAUSED_BY_FUTURE_API") })
        }

        coEvery { client.listRules(capture(request)) } returns response

        val result = EventBridgeKtorTemplate(client).listRules(
            eventBusName = "orders",
            namePrefix = "future",
            limit = 37,
            nextToken = "current-page",
        )

        request.captured.eventBusName shouldBeEqualTo "orders"
        request.captured.namePrefix shouldBeEqualTo "future"
        request.captured.limit shouldBeEqualTo 37
        request.captured.nextToken shouldBeEqualTo "current-page"
        result.nextToken shouldBeEqualTo "next-page"
        result.rules?.single()?.state shouldBeEqualTo RuleState.SdkUnknown("PAUSED_BY_FUTURE_API")
    }
}
