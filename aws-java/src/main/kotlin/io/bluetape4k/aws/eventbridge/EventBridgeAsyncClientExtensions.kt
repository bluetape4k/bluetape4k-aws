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

/** EventBridge 이벤트 버스를 비동기로 생성합니다. */
fun EventBridgeAsyncClient.createEventBusAsync(name: String): CompletableFuture<CreateEventBusResponse> =
    createEventBus(createEventBusRequestOf(name))

/** 숨겨진 정리 작업 없이 EventBridge 이벤트 버스를 비동기로 삭제합니다. */
fun EventBridgeAsyncClient.deleteEventBusAsync(name: String): CompletableFuture<DeleteEventBusResponse> =
    deleteEventBus(deleteEventBusRequestOf(name))

/** 규칙을 비동기로 생성하거나 갱신합니다. */
fun EventBridgeAsyncClient.putRuleAsync(
    name: String,
    eventBusName: String? = null,
    eventPattern: String? = null,
    scheduleExpression: String? = null,
    state: RuleState? = null,
    description: String? = null,
): CompletableFuture<PutRuleResponse> =
    putRule(putRuleRequestOf(name, eventBusName, eventPattern, scheduleExpression, state, description))

/** 호출자가 대상을 제거한 뒤 규칙을 비동기로 삭제합니다. */
fun EventBridgeAsyncClient.deleteRuleAsync(
    name: String,
    eventBusName: String? = null,
    force: Boolean? = null,
): CompletableFuture<DeleteRuleResponse> =
    deleteRule(deleteRuleRequestOf(name, eventBusName, force))

/** 대상을 비동기로 추가하거나 갱신하고 부분 실패 원본 응답을 보존합니다. */
fun EventBridgeAsyncClient.putTargetsAsync(
    rule: String,
    targets: List<Target>,
    eventBusName: String? = null,
): CompletableFuture<PutTargetsResponse> =
    putTargets(putTargetsRequestOf(rule, targets, eventBusName))

/** 대상을 비동기로 제거하고 부분 실패 원본 응답을 보존합니다. */
fun EventBridgeAsyncClient.removeTargetsAsync(
    rule: String,
    ids: List<String>,
    eventBusName: String? = null,
    force: Boolean? = null,
): CompletableFuture<RemoveTargetsResponse> =
    removeTargets(removeTargetsRequestOf(rule, ids, eventBusName, force))

/** 규칙 목록을 비동기로 조회합니다. */
fun EventBridgeAsyncClient.listRulesAsync(
    eventBusName: String? = null,
    namePrefix: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): CompletableFuture<ListRulesResponse> =
    listRules(listRulesRequestOf(eventBusName, namePrefix, limit, nextToken))

/** 규칙 대상을 비동기로 조회합니다. */
fun EventBridgeAsyncClient.listTargetsByRuleAsync(
    rule: String,
    eventBusName: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): CompletableFuture<ListTargetsByRuleResponse> =
    listTargetsByRule(listTargetsByRuleRequestOf(rule, eventBusName, limit, nextToken))

/** 숨겨진 배치 처리 없이 SDK 요청 하나로 이벤트를 비동기 게시합니다. */
fun EventBridgeAsyncClient.putEventsAsync(
    entries: List<PutEventsRequestEntry>,
): CompletableFuture<PutEventsResponse> =
    putEvents(putEventsRequestOf(entries))
