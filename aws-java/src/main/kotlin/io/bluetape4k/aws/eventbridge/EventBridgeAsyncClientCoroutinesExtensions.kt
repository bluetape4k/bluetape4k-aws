package io.bluetape4k.aws.eventbridge

import kotlinx.coroutines.future.await
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

/** Coroutine adapter for [createEventBusAsync]. */
suspend fun EventBridgeAsyncClient.createEventBus(name: String): CreateEventBusResponse =
    createEventBusAsync(name).await()

/** Coroutine adapter for [deleteEventBusAsync]. */
suspend fun EventBridgeAsyncClient.deleteEventBus(name: String): DeleteEventBusResponse =
    deleteEventBusAsync(name).await()

/** Coroutine adapter for [putRuleAsync]. */
suspend fun EventBridgeAsyncClient.putRule(
    name: String,
    eventBusName: String? = null,
    eventPattern: String? = null,
    scheduleExpression: String? = null,
    state: RuleState? = null,
    description: String? = null,
): PutRuleResponse =
    putRuleAsync(name, eventBusName, eventPattern, scheduleExpression, state, description).await()

/** Coroutine adapter for [deleteRuleAsync]. */
suspend fun EventBridgeAsyncClient.deleteRule(
    name: String,
    eventBusName: String? = null,
    force: Boolean? = null,
): DeleteRuleResponse =
    deleteRuleAsync(name, eventBusName, force).await()

/** Coroutine adapter for [putTargetsAsync]. */
suspend fun EventBridgeAsyncClient.putTargets(
    rule: String,
    targets: List<Target>,
    eventBusName: String? = null,
): PutTargetsResponse =
    putTargetsAsync(rule, targets, eventBusName).await()

/** Coroutine adapter for [removeTargetsAsync]. */
suspend fun EventBridgeAsyncClient.removeTargets(
    rule: String,
    ids: List<String>,
    eventBusName: String? = null,
    force: Boolean? = null,
): RemoveTargetsResponse =
    removeTargetsAsync(rule, ids, eventBusName, force).await()

/** Coroutine adapter for [listRulesAsync]. */
suspend fun EventBridgeAsyncClient.listRules(
    eventBusName: String? = null,
    namePrefix: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListRulesResponse =
    listRulesAsync(eventBusName, namePrefix, limit, nextToken).await()

/** Coroutine adapter for [listTargetsByRuleAsync]. */
suspend fun EventBridgeAsyncClient.listTargetsByRule(
    rule: String,
    eventBusName: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListTargetsByRuleResponse =
    listTargetsByRuleAsync(rule, eventBusName, limit, nextToken).await()

/**
 * Publishes events with one awaited SDK request.
 *
 * Cancellation and SDK exceptions propagate from `await()`.
 */
suspend fun EventBridgeAsyncClient.putEvents(
    entries: List<PutEventsRequestEntry>,
): PutEventsResponse =
    putEventsAsync(entries).await()
