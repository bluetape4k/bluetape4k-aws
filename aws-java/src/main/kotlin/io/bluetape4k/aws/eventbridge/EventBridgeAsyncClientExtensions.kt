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
import java.util.concurrent.CompletableFuture

/** Creates an EventBridge event bus asynchronously. */
fun EventBridgeAsyncClient.createEventBusAsync(name: String): CompletableFuture<CreateEventBusResponse> =
    createEventBus(createEventBusRequestOf(name))

/** Deletes an EventBridge event bus asynchronously without hidden cleanup. */
fun EventBridgeAsyncClient.deleteEventBusAsync(name: String): CompletableFuture<DeleteEventBusResponse> =
    deleteEventBus(deleteEventBusRequestOf(name))

/** Creates or updates a rule asynchronously. */
fun EventBridgeAsyncClient.putRuleAsync(
    name: String,
    eventBusName: String? = null,
    eventPattern: String? = null,
    scheduleExpression: String? = null,
    state: RuleState? = null,
    description: String? = null,
): CompletableFuture<PutRuleResponse> =
    putRule(putRuleRequestOf(name, eventBusName, eventPattern, scheduleExpression, state, description))

/** Deletes a rule asynchronously after the caller removes targets. */
fun EventBridgeAsyncClient.deleteRuleAsync(
    name: String,
    eventBusName: String? = null,
    force: Boolean? = null,
): CompletableFuture<DeleteRuleResponse> =
    deleteRule(deleteRuleRequestOf(name, eventBusName, force))

/** Adds or updates targets asynchronously and preserves the raw partial-failure response. */
fun EventBridgeAsyncClient.putTargetsAsync(
    rule: String,
    targets: List<Target>,
    eventBusName: String? = null,
): CompletableFuture<PutTargetsResponse> =
    putTargets(putTargetsRequestOf(rule, targets, eventBusName))

/** Removes targets asynchronously and preserves the raw partial-failure response. */
fun EventBridgeAsyncClient.removeTargetsAsync(
    rule: String,
    ids: List<String>,
    eventBusName: String? = null,
    force: Boolean? = null,
): CompletableFuture<RemoveTargetsResponse> =
    removeTargets(removeTargetsRequestOf(rule, ids, eventBusName, force))

/** Lists rules asynchronously. */
fun EventBridgeAsyncClient.listRulesAsync(
    eventBusName: String? = null,
    namePrefix: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): CompletableFuture<ListRulesResponse> =
    listRules(listRulesRequestOf(eventBusName, namePrefix, limit, nextToken))

/** Lists rule targets asynchronously. */
fun EventBridgeAsyncClient.listTargetsByRuleAsync(
    rule: String,
    eventBusName: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): CompletableFuture<ListTargetsByRuleResponse> =
    listTargetsByRule(listTargetsByRuleRequestOf(rule, eventBusName, limit, nextToken))

/** Publishes events asynchronously with one SDK request and no hidden batching. */
fun EventBridgeAsyncClient.putEventsAsync(
    entries: List<PutEventsRequestEntry>,
): CompletableFuture<PutEventsResponse> =
    putEvents(putEventsRequestOf(entries))
