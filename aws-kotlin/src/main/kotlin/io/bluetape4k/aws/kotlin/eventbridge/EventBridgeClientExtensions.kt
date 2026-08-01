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

/** EventBridge 이벤트 버스를 생성하고 SDK 원본 응답을 반환합니다. */
suspend fun EventBridgeClient.createEventBus(name: String): CreateEventBusResponse =
    createEventBus(createEventBusRequestOf(name))

/** 숨겨진 규칙 정리 없이 EventBridge 이벤트 버스를 삭제합니다. */
suspend fun EventBridgeClient.deleteEventBus(name: String): DeleteEventBusResponse =
    deleteEventBus(deleteEventBusRequestOf(name))

/**
 * 규칙을 생성하거나 갱신하고 SDK 원본 응답을 반환합니다.
 *
 * [eventPattern] 또는 [scheduleExpression] 중 하나가 필요합니다.
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

/** 호출자가 대상을 제거하고 제거 실패를 확인한 뒤 규칙을 삭제합니다. */
suspend fun EventBridgeClient.deleteRule(
    name: String,
    eventBusName: String? = null,
    force: Boolean? = null,
): DeleteRuleResponse =
    deleteRule(deleteRuleRequestOf(name, eventBusName, force))

/**
 * 대상을 추가하거나 갱신하고 부분 실패 원본 상세 정보를 보존합니다.
 */
suspend fun EventBridgeClient.putTargets(
    rule: String,
    targets: List<Target>,
    eventBusName: String? = null,
): PutTargetsResponse =
    putTargets(putTargetsRequestOf(rule, targets, eventBusName))

/**
 * 대상을 제거하고 부분 실패 원본 상세 정보를 보존합니다.
 */
suspend fun EventBridgeClient.removeTargets(
    rule: String,
    ids: List<String>,
    eventBusName: String? = null,
    force: Boolean? = null,
): RemoveTargetsResponse =
    removeTargets(removeTargetsRequestOf(rule, ids, eventBusName, force))

/** EventBridge 규칙 목록을 조회합니다. */
suspend fun EventBridgeClient.listRules(
    eventBusName: String? = null,
    namePrefix: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListRulesResponse =
    listRules(listRulesRequestOf(eventBusName, namePrefix, limit, nextToken))

/** 규칙에 연결된 대상 목록을 조회합니다. */
suspend fun EventBridgeClient.listTargetsByRule(
    rule: String,
    eventBusName: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListTargetsByRuleResponse =
    listTargetsByRule(listTargetsByRuleRequestOf(rule, eventBusName, limit, nextToken))

/**
 * SDK 요청 하나로 이벤트를 게시합니다.
 *
 * 숨겨진 배치, 재시도 또는 Boolean 성공 축약을 수행하지 않습니다. 원본 응답의 실패 항목 필드를 확인하세요.
 */
suspend fun EventBridgeClient.putEvents(
    entries: List<PutEventsRequestEntry>,
): PutEventsResponse =
    putEvents(putEventsRequestOf(entries))
