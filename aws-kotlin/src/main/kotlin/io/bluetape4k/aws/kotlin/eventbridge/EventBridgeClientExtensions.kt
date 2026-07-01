package io.bluetape4k.aws.kotlin.eventbridge

import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.createEventBus
import aws.sdk.kotlin.services.eventbridge.deleteEventBus
import aws.sdk.kotlin.services.eventbridge.deleteRule
import aws.sdk.kotlin.services.eventbridge.listRules
import aws.sdk.kotlin.services.eventbridge.listTargetsByRule
import aws.sdk.kotlin.services.eventbridge.model.CreateEventBusResponse
import aws.sdk.kotlin.services.eventbridge.model.DeleteEventBusResponse
import aws.sdk.kotlin.services.eventbridge.model.DeleteRuleResponse
import aws.sdk.kotlin.services.eventbridge.model.ListRulesResponse
import aws.sdk.kotlin.services.eventbridge.model.ListTargetsByRuleResponse
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequestEntry
import aws.sdk.kotlin.services.eventbridge.model.PutEventsResponse
import aws.sdk.kotlin.services.eventbridge.model.PutRuleResponse
import aws.sdk.kotlin.services.eventbridge.model.PutTargetsResponse
import aws.sdk.kotlin.services.eventbridge.model.RemoveTargetsResponse
import aws.sdk.kotlin.services.eventbridge.model.RuleState
import aws.sdk.kotlin.services.eventbridge.model.Target
import aws.sdk.kotlin.services.eventbridge.putEvents
import aws.sdk.kotlin.services.eventbridge.putRule
import aws.sdk.kotlin.services.eventbridge.putTargets
import aws.sdk.kotlin.services.eventbridge.removeTargets
import io.bluetape4k.aws.kotlin.eventbridge.model.createEventBusRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.deleteEventBusRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.deleteRuleRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.listRulesRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.listTargetsByRuleRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.putEventsRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.putRuleRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.putTargetsRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.removeTargetsRequestOf

/** Creates an EventBridge event bus and returns the raw SDK response. */
suspend fun EventBridgeClient.createEventBus(name: String): CreateEventBusResponse =
    createEventBus(createEventBusRequestOf(name))

/** Deletes an EventBridge event bus without hidden rule cleanup. */
suspend fun EventBridgeClient.deleteEventBus(name: String): DeleteEventBusResponse =
    deleteEventBus(deleteEventBusRequestOf(name))

/**
 * Creates or updates a rule and returns the raw SDK response.
 *
 * Either [eventPattern] or [scheduleExpression] is required.
 */
suspend fun EventBridgeClient.putRule(
    name: String,
    eventBusName: String? = null,
    eventPattern: String? = null,
    scheduleExpression: String? = null,
    state: RuleState? = null,
    description: String? = null,
): PutRuleResponse =
    putRule(putRuleRequestOf(name, eventBusName, eventPattern, scheduleExpression, state, description))

/** Deletes a rule after callers remove targets and inspect removal failures. */
suspend fun EventBridgeClient.deleteRule(
    name: String,
    eventBusName: String? = null,
    force: Boolean? = null,
): DeleteRuleResponse =
    deleteRule(deleteRuleRequestOf(name, eventBusName, force))

/**
 * Adds or updates targets and preserves raw partial-failure details.
 */
suspend fun EventBridgeClient.putTargets(
    rule: String,
    targets: List<Target>,
    eventBusName: String? = null,
): PutTargetsResponse =
    putTargets(putTargetsRequestOf(rule, targets, eventBusName))

/**
 * Removes targets and preserves raw partial-failure details.
 */
suspend fun EventBridgeClient.removeTargets(
    rule: String,
    ids: List<String>,
    eventBusName: String? = null,
    force: Boolean? = null,
): RemoveTargetsResponse =
    removeTargets(removeTargetsRequestOf(rule, ids, eventBusName, force))

/** Lists EventBridge rules. */
suspend fun EventBridgeClient.listRules(
    eventBusName: String? = null,
    namePrefix: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListRulesResponse =
    listRules(listRulesRequestOf(eventBusName, namePrefix, limit, nextToken))

/** Lists targets attached to a rule. */
suspend fun EventBridgeClient.listTargetsByRule(
    rule: String,
    eventBusName: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListTargetsByRuleResponse =
    listTargetsByRule(listTargetsByRuleRequestOf(rule, eventBusName, limit, nextToken))

/**
 * Publishes events with one SDK request.
 *
 * No hidden batching, retry, or Boolean success collapse is performed. Inspect
 * the raw response failed-entry fields.
 */
suspend fun EventBridgeClient.putEvents(
    entries: List<PutEventsRequestEntry>,
): PutEventsResponse =
    putEvents(putEventsRequestOf(entries))
