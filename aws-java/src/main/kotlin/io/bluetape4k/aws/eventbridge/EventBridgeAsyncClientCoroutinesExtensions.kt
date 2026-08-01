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

/** [createEventBusAsync]의 코루틴 어댑터입니다. */
suspend fun EventBridgeAsyncClient.createEventBus(name: String): CreateEventBusResponse =
    createEventBusAsync(name).await()

/** [deleteEventBusAsync]의 코루틴 어댑터입니다. */
suspend fun EventBridgeAsyncClient.deleteEventBus(name: String): DeleteEventBusResponse =
    deleteEventBusAsync(name).await()

/** [putRuleAsync]의 코루틴 어댑터입니다. */
suspend fun EventBridgeAsyncClient.putRule(
    name: String,
    eventBusName: String? = null,
    eventPattern: String? = null,
    scheduleExpression: String? = null,
    state: RuleState? = null,
    description: String? = null,
): PutRuleResponse =
    putRuleAsync(name, eventBusName, eventPattern, scheduleExpression, state, description).await()

/** [deleteRuleAsync]의 코루틴 어댑터입니다. */
suspend fun EventBridgeAsyncClient.deleteRule(
    name: String,
    eventBusName: String? = null,
    force: Boolean? = null,
): DeleteRuleResponse =
    deleteRuleAsync(name, eventBusName, force).await()

/** [putTargetsAsync]의 코루틴 어댑터입니다. */
suspend fun EventBridgeAsyncClient.putTargets(
    rule: String,
    targets: List<Target>,
    eventBusName: String? = null,
): PutTargetsResponse =
    putTargetsAsync(rule, targets, eventBusName).await()

/** [removeTargetsAsync]의 코루틴 어댑터입니다. */
suspend fun EventBridgeAsyncClient.removeTargets(
    rule: String,
    ids: List<String>,
    eventBusName: String? = null,
    force: Boolean? = null,
): RemoveTargetsResponse =
    removeTargetsAsync(rule, ids, eventBusName, force).await()

/** [listRulesAsync]의 코루틴 어댑터입니다. */
suspend fun EventBridgeAsyncClient.listRules(
    eventBusName: String? = null,
    namePrefix: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListRulesResponse =
    listRulesAsync(eventBusName, namePrefix, limit, nextToken).await()

/** [listTargetsByRuleAsync]의 코루틴 어댑터입니다. */
suspend fun EventBridgeAsyncClient.listTargetsByRule(
    rule: String,
    eventBusName: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListTargetsByRuleResponse =
    listTargetsByRuleAsync(rule, eventBusName, limit, nextToken).await()

/**
 * SDK 요청 하나를 기다려 이벤트를 게시합니다.
 *
 * 취소와 SDK 예외는 `await()`에서 그대로 전파됩니다.
 */
suspend fun EventBridgeAsyncClient.putEvents(
    entries: List<PutEventsRequestEntry>,
): PutEventsResponse =
    putEventsAsync(entries).await()
