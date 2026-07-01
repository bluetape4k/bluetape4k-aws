package io.bluetape4k.aws.eventbridge

import io.bluetape4k.aws.eventbridge.model.createEventBusRequestOf
import io.bluetape4k.aws.eventbridge.model.deleteEventBusRequestOf
import io.bluetape4k.aws.eventbridge.model.deleteRuleRequestOf
import io.bluetape4k.aws.eventbridge.model.listRulesRequestOf
import io.bluetape4k.aws.eventbridge.model.listTargetsByRuleRequestOf
import io.bluetape4k.aws.eventbridge.model.putEventsRequestOf
import io.bluetape4k.aws.eventbridge.model.putRuleRequestOf
import io.bluetape4k.aws.eventbridge.model.putTargetsRequestOf
import io.bluetape4k.aws.eventbridge.model.removeTargetsRequestOf
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
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
 * Creates an EventBridge event bus and returns the raw SDK response.
 */
fun EventBridgeClient.createEventBus(name: String): CreateEventBusResponse =
    createEventBus(createEventBusRequestOf(name))

/**
 * Deletes an EventBridge event bus.
 *
 * Delete rules before deleting a custom or partner bus; this helper performs no
 * hidden cleanup.
 */
fun EventBridgeClient.deleteEventBus(name: String): DeleteEventBusResponse =
    deleteEventBus(deleteEventBusRequestOf(name))

/**
 * Creates or updates a rule and returns the raw SDK response.
 *
 * EventBridge requires either [eventPattern] or [scheduleExpression]. Omitted
 * fields are not merged by this wrapper.
 */
fun EventBridgeClient.putRule(
    name: String,
    eventBusName: String? = null,
    eventPattern: String? = null,
    scheduleExpression: String? = null,
    state: RuleState? = null,
    description: String? = null,
): PutRuleResponse =
    putRule(putRuleRequestOf(name, eventBusName, eventPattern, scheduleExpression, state, description))

/**
 * Deletes a rule after the caller removes targets.
 */
fun EventBridgeClient.deleteRule(
    name: String,
    eventBusName: String? = null,
    force: Boolean? = null,
): DeleteRuleResponse =
    deleteRule(deleteRuleRequestOf(name, eventBusName, force))

/**
 * Adds or updates targets for a rule.
 *
 * EventBridge may partially fail the operation. Inspect the returned
 * [PutTargetsResponse] failed-entry count and entries.
 */
fun EventBridgeClient.putTargets(
    rule: String,
    targets: List<Target>,
    eventBusName: String? = null,
): PutTargetsResponse =
    putTargets(putTargetsRequestOf(rule, targets, eventBusName))

/**
 * Removes targets from a rule.
 *
 * EventBridge may partially fail the operation. Inspect the returned
 * [RemoveTargetsResponse] failed-entry count and entries before deleting a rule.
 */
fun EventBridgeClient.removeTargets(
    rule: String,
    ids: List<String>,
    eventBusName: String? = null,
    force: Boolean? = null,
): RemoveTargetsResponse =
    removeTargets(removeTargetsRequestOf(rule, ids, eventBusName, force))

/**
 * Lists rules on an event bus.
 */
fun EventBridgeClient.listRules(
    eventBusName: String? = null,
    namePrefix: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListRulesResponse =
    listRules(listRulesRequestOf(eventBusName, namePrefix, limit, nextToken))

/**
 * Lists targets attached to a rule.
 */
fun EventBridgeClient.listTargetsByRule(
    rule: String,
    eventBusName: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListTargetsByRuleResponse =
    listTargetsByRule(listTargetsByRuleRequestOf(rule, eventBusName, limit, nextToken))

/**
 * Publishes custom events with one SDK request.
 *
 * This helper does not batch, retry, or collapse partial success to a Boolean.
 * Inspect [PutEventsResponse] failed-entry count and entries.
 */
fun EventBridgeClient.putEvents(
    entries: List<PutEventsRequestEntry>,
): PutEventsResponse =
    putEvents(putEventsRequestOf(entries))
