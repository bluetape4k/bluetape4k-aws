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
 * EventBridge 이벤트 버스를 생성하고 SDK 원본 응답을 반환합니다.
 */
fun EventBridgeClient.createEventBus(name: String): CreateEventBusResponse =
    createEventBus(createEventBusRequestOf(name))

/**
 * EventBridge 이벤트 버스를 삭제합니다.
 *
 * 사용자 정의 또는 파트너 버스를 삭제하기 전에 규칙을 삭제해야 합니다. 이 도우미는 숨겨진 정리를 수행하지 않습니다.
 */
fun EventBridgeClient.deleteEventBus(name: String): DeleteEventBusResponse =
    deleteEventBus(deleteEventBusRequestOf(name))

/**
 * 규칙을 생성하거나 갱신하고 SDK 원본 응답을 반환합니다.
 *
 * EventBridge에는 [eventPattern] 또는 [scheduleExpression] 중 하나가 필요합니다.
 * 이 래퍼는 생략한 필드를 병합하지 않습니다.
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
 * 호출자가 대상을 제거한 뒤 규칙을 삭제합니다.
 */
fun EventBridgeClient.deleteRule(
    name: String,
    eventBusName: String? = null,
    force: Boolean? = null,
): DeleteRuleResponse =
    deleteRule(deleteRuleRequestOf(name, eventBusName, force))

/**
 * 규칙의 대상을 추가하거나 갱신합니다.
 *
 * EventBridge 작업은 부분적으로 실패할 수 있습니다. 반환된 [PutTargetsResponse]의 실패 항목 수와 항목을 확인하세요.
 */
fun EventBridgeClient.putTargets(
    rule: String,
    targets: List<Target>,
    eventBusName: String? = null,
): PutTargetsResponse =
    putTargets(putTargetsRequestOf(rule, targets, eventBusName))

/**
 * 규칙에서 대상을 제거합니다.
 *
 * EventBridge 작업은 부분적으로 실패할 수 있습니다. 규칙을 삭제하기 전에 반환된
 * [RemoveTargetsResponse]의 실패 항목 수와 항목을 확인하세요.
 */
fun EventBridgeClient.removeTargets(
    rule: String,
    ids: List<String>,
    eventBusName: String? = null,
    force: Boolean? = null,
): RemoveTargetsResponse =
    removeTargets(removeTargetsRequestOf(rule, ids, eventBusName, force))

/**
 * 이벤트 버스의 규칙 목록을 조회합니다.
 */
fun EventBridgeClient.listRules(
    eventBusName: String? = null,
    namePrefix: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListRulesResponse =
    listRules(listRulesRequestOf(eventBusName, namePrefix, limit, nextToken))

/**
 * 규칙에 연결된 대상 목록을 조회합니다.
 */
fun EventBridgeClient.listTargetsByRule(
    rule: String,
    eventBusName: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
): ListTargetsByRuleResponse =
    listTargetsByRule(listTargetsByRuleRequestOf(rule, eventBusName, limit, nextToken))

/**
 * SDK 요청 하나로 사용자 정의 이벤트를 게시합니다.
 *
 * 이 도우미는 배치, 재시도 또는 부분 성공을 Boolean으로 축약하지 않습니다.
 * [PutEventsResponse]의 실패 항목 수와 항목을 확인하세요.
 */
fun EventBridgeClient.putEvents(
    entries: List<PutEventsRequestEntry>,
): PutEventsResponse =
    putEvents(putEventsRequestOf(entries))
