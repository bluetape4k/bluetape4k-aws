package io.bluetape4k.aws.spring.eventbridge

import io.bluetape4k.aws.eventbridge.createEventBus
import io.bluetape4k.aws.eventbridge.deleteEventBus
import io.bluetape4k.aws.eventbridge.deleteRule
import io.bluetape4k.aws.eventbridge.listRules
import io.bluetape4k.aws.eventbridge.listTargetsByRule
import io.bluetape4k.aws.eventbridge.putEvents
import io.bluetape4k.aws.eventbridge.putRule
import io.bluetape4k.aws.eventbridge.putTargets
import io.bluetape4k.aws.eventbridge.removeTargets
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import software.amazon.awssdk.services.eventbridge.model.CreateEventBusResponse
import software.amazon.awssdk.services.eventbridge.model.DeleteEventBusResponse
import software.amazon.awssdk.services.eventbridge.model.DeleteRuleResponse
import software.amazon.awssdk.services.eventbridge.model.ListRulesResponse
import software.amazon.awssdk.services.eventbridge.model.ListTargetsByRuleResponse
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse
import software.amazon.awssdk.services.eventbridge.model.PutRuleResponse
import software.amazon.awssdk.services.eventbridge.model.PutTargetsResponse
import software.amazon.awssdk.services.eventbridge.model.RemoveTargetsResponse
import software.amazon.awssdk.services.eventbridge.model.RuleState
import software.amazon.awssdk.services.eventbridge.model.Target

/**
 * Default [EventBridgeOperations] backed by AWS SDK v2 [EventBridgeAsyncClient].
 *
 * ## Contract
 *
 * Delegates to bluetape4k EventBridge coroutine adapters. Cancellation and SDK
 * exceptions propagate unchanged from the underlying `CompletableFuture`.
 */
class EventBridgeCoroutinesTemplate(
    private val eventBridgeAsyncClient: EventBridgeAsyncClient,
    private val properties: EventBridgeProperties,
) : EventBridgeOperations {

    override suspend fun createEventBus(name: String): CreateEventBusResponse =
        eventBridgeAsyncClient.createEventBus(name)

    override suspend fun deleteEventBus(name: String): DeleteEventBusResponse =
        eventBridgeAsyncClient.deleteEventBus(name)

    override suspend fun putRule(
        name: String,
        eventBusName: String?,
        eventPattern: String?,
        scheduleExpression: String?,
        state: RuleState?,
        description: String?,
    ): PutRuleResponse =
        eventBridgeAsyncClient.putRule(
            name = name,
            eventBusName = resolveEventBusName(eventBusName),
            eventPattern = eventPattern,
            scheduleExpression = scheduleExpression,
            state = state,
            description = description,
        )

    override suspend fun deleteRule(
        name: String,
        eventBusName: String?,
        force: Boolean?,
    ): DeleteRuleResponse =
        eventBridgeAsyncClient.deleteRule(name, resolveEventBusName(eventBusName), force)

    override suspend fun putTargets(
        rule: String,
        targets: List<Target>,
        eventBusName: String?,
    ): PutTargetsResponse =
        eventBridgeAsyncClient.putTargets(rule, targets, resolveEventBusName(eventBusName))

    override suspend fun removeTargets(
        rule: String,
        ids: List<String>,
        eventBusName: String?,
        force: Boolean?,
    ): RemoveTargetsResponse =
        eventBridgeAsyncClient.removeTargets(rule, ids, resolveEventBusName(eventBusName), force)

    override suspend fun listRules(
        eventBusName: String?,
        namePrefix: String?,
        limit: Int?,
        nextToken: String?,
    ): ListRulesResponse =
        eventBridgeAsyncClient.listRules(resolveEventBusName(eventBusName), namePrefix, limit, nextToken)

    override suspend fun listTargetsByRule(
        rule: String,
        eventBusName: String?,
        limit: Int?,
        nextToken: String?,
    ): ListTargetsByRuleResponse =
        eventBridgeAsyncClient.listTargetsByRule(rule, resolveEventBusName(eventBusName), limit, nextToken)

    override suspend fun putEvents(entries: List<PutEventsRequestEntry>): PutEventsResponse =
        eventBridgeAsyncClient.putEvents(entries)

    private fun resolveEventBusName(eventBusName: String?): String? =
        eventBusName ?: properties.defaultEventBusName
}
