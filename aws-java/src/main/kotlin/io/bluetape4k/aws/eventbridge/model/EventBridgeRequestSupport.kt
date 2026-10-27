package io.bluetape4k.aws.eventbridge.model

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.eventbridge.model.CreateEventBusRequest
import software.amazon.awssdk.services.eventbridge.model.DeleteEventBusRequest
import software.amazon.awssdk.services.eventbridge.model.DeleteRuleRequest
import software.amazon.awssdk.services.eventbridge.model.ListRulesRequest
import software.amazon.awssdk.services.eventbridge.model.ListTargetsByRuleRequest
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import software.amazon.awssdk.services.eventbridge.model.PutRuleRequest
import software.amazon.awssdk.services.eventbridge.model.PutTargetsRequest
import software.amazon.awssdk.services.eventbridge.model.RemoveTargetsRequest
import software.amazon.awssdk.services.eventbridge.model.RuleState
import software.amazon.awssdk.services.eventbridge.model.Target
import java.time.Instant

@PublishedApi
internal const val MAX_EVENTBRIDGE_BATCH_SIZE = 10

@PublishedApi
internal const val MIN_EVENTBRIDGE_LIST_LIMIT = 1

@PublishedApi
internal const val MAX_EVENTBRIDGE_LIST_LIMIT = 100

@PublishedApi
internal fun <T> List<T>.requireSizeInOneToTen(name: String) {
    require(isNotEmpty()) { "$name must not be empty." }
    require(size <= MAX_EVENTBRIDGE_BATCH_SIZE) { "$name must not contain more than $MAX_EVENTBRIDGE_BATCH_SIZE items." }
}

@PublishedApi
internal fun Iterable<String>.requireNoBlankValues(name: String) {
    forEach { it.requireNotBlank(name) }
}

@PublishedApi
internal fun Int.requireEventBridgeListLimit(name: String) {
    requireInRange(MIN_EVENTBRIDGE_LIST_LIMIT, MAX_EVENTBRIDGE_LIST_LIMIT, name)
}

/**
 * [CreateEventBusRequest]의 SDK builder 필드만 사용해 구성합니다.
 * 필수 bus name과 최종 검증이 필요한 경우 [createEventBusRequestOf]를 사용하세요.
 */
inline fun createEventBusRequest(
    builder: CreateEventBusRequest.Builder.() -> Unit,
): CreateEventBusRequest = CreateEventBusRequest.builder()
    .apply(builder)
    .build()

/**
 * 버스 이름으로 [CreateEventBusRequest]를 구성합니다.
 */
inline fun createEventBusRequestOf(
    name: String,
    builder: CreateEventBusRequest.Builder.() -> Unit = {},
): CreateEventBusRequest {
    name.requireNotBlank("name")
    return CreateEventBusRequest.builder()
        .name(name)
        .apply(builder)
        .build()
        .also { it.name().requireNotBlank("name") }
}

/**
 * [DeleteEventBusRequest]의 SDK builder 필드만 사용해 구성합니다.
 * 필수 bus name과 최종 검증이 필요한 경우 [deleteEventBusRequestOf]를 사용하세요.
 */
inline fun deleteEventBusRequest(
    builder: DeleteEventBusRequest.Builder.() -> Unit,
): DeleteEventBusRequest = DeleteEventBusRequest.builder()
    .apply(builder)
    .build()

/**
 * 버스 이름으로 [DeleteEventBusRequest]를 구성합니다.
 *
 * 사용자 정의 또는 파트너 이벤트 버스를 삭제하기 전에 규칙을 삭제하세요.
 */
inline fun deleteEventBusRequestOf(
    name: String,
    builder: DeleteEventBusRequest.Builder.() -> Unit = {},
): DeleteEventBusRequest {
    name.requireNotBlank("name")
    return DeleteEventBusRequest.builder()
        .name(name)
        .apply(builder)
        .build()
        .also { it.name().requireNotBlank("name") }
}

/**
 * [PutRuleRequest]의 SDK builder 필드만 사용해 구성합니다.
 * 필수 name·pattern/schedule 검증이 필요한 경우 [putRuleRequestOf]를 사용하세요.
 */
inline fun putRuleRequest(
    builder: PutRuleRequest.Builder.() -> Unit,
): PutRuleRequest = PutRuleRequest.builder()
    .apply(builder)
    .build()

/**
 * [PutRuleRequest]를 구성합니다.
 *
 * EventBridge에는 [eventPattern] 또는 [scheduleExpression] 중 하나가 필요합니다.
 * 이 도우미는 생략한 필드를 기존 규칙과 병합하지 않습니다.
 */
inline fun putRuleRequestOf(
    name: String,
    eventBusName: String? = null,
    eventPattern: String? = null,
    scheduleExpression: String? = null,
    state: RuleState? = null,
    description: String? = null,
    builder: PutRuleRequest.Builder.() -> Unit = {},
): PutRuleRequest {
    name.requireNotBlank("name")
    eventBusName?.requireNotBlank("eventBusName")
    eventPattern?.requireNotBlank("eventPattern")
    scheduleExpression?.requireNotBlank("scheduleExpression")
    require(!eventPattern.isNullOrBlank() || !scheduleExpression.isNullOrBlank()) {
        "eventPattern or scheduleExpression must be provided."
    }

    return PutRuleRequest.builder()
        .name(name)
        .also {
            eventBusName?.let(it::eventBusName)
            eventPattern?.let(it::eventPattern)
            scheduleExpression?.let(it::scheduleExpression)
            state?.let(it::state)
            description?.let(it::description)
        }
        .apply(builder)
        .build()
        .also { request ->
            request.name().requireNotBlank("name")
            request.eventBusName()?.requireNotBlank("eventBusName")
            request.eventPattern()?.requireNotBlank("eventPattern")
            request.scheduleExpression()?.requireNotBlank("scheduleExpression")
            require(!request.eventPattern().isNullOrBlank() || !request.scheduleExpression().isNullOrBlank()) {
                "eventPattern or scheduleExpression must be provided."
            }
            request.description()?.requireNotBlank("description")
        }
}

/**
 * [DeleteRuleRequest]의 SDK builder 필드만 사용해 구성합니다.
 * 필수 rule name과 최종 검증이 필요한 경우 [deleteRuleRequestOf]를 사용하세요.
 */
inline fun deleteRuleRequest(
    builder: DeleteRuleRequest.Builder.() -> Unit,
): DeleteRuleRequest = DeleteRuleRequest.builder()
    .apply(builder)
    .build()

/**
 * [DeleteRuleRequest]를 구성합니다.
 *
 * 규칙을 삭제하기 전에 대상을 제거하고 제거 실패 항목이 있는지 확인하세요.
 */
inline fun deleteRuleRequestOf(
    name: String,
    eventBusName: String? = null,
    force: Boolean? = null,
    builder: DeleteRuleRequest.Builder.() -> Unit = {},
): DeleteRuleRequest {
    name.requireNotBlank("name")
    eventBusName?.requireNotBlank("eventBusName")
    return DeleteRuleRequest.builder()
        .name(name)
        .also {
            eventBusName?.let(it::eventBusName)
            force?.let(it::force)
        }
        .apply(builder)
        .build()
        .also { request ->
            request.name().requireNotBlank("name")
            request.eventBusName()?.requireNotBlank("eventBusName")
        }
}

/**
 * 필수 id와 ARN으로 [Target]을 구성합니다.
 */
inline fun targetOf(
    id: String,
    arn: String,
    builder: Target.Builder.() -> Unit = {},
): Target {
    id.requireNotBlank("id")
    arn.requireNotBlank("arn")
    return Target.builder()
        .id(id)
        .arn(arn)
        .apply(builder)
        .build()
}

/**
 * [PutTargetsRequest]의 SDK builder 필드만 사용해 구성합니다.
 * 필수 rule·target 목록과 1..10 limit 검증이 필요한 경우 [putTargetsRequestOf]를 사용하세요.
 */
inline fun putTargetsRequest(
    builder: PutTargetsRequest.Builder.() -> Unit,
): PutTargetsRequest = PutTargetsRequest.builder()
    .apply(builder)
    .build()

/**
 * EventBridge의 요청당 대상 10개 제한에 맞춰 [PutTargetsRequest]를 구성합니다.
 *
 * 원본 응답에는 실패 항목 수와 상세 정보가 있으므로 호출자가 이를 확인해야 합니다.
 */
inline fun putTargetsRequestOf(
    rule: String,
    targets: List<Target>,
    eventBusName: String? = null,
    builder: PutTargetsRequest.Builder.() -> Unit = {},
): PutTargetsRequest {
    rule.requireNotBlank("rule")
    eventBusName?.requireNotBlank("eventBusName")
    targets.requireSizeInOneToTen("targets")

    return PutTargetsRequest.builder()
        .rule(rule)
        .targets(targets)
        .also { eventBusName?.let(it::eventBusName) }
        .apply(builder)
        .build()
        .also {
            it.rule().requireNotBlank("rule")
            it.eventBusName()?.requireNotBlank("eventBusName")
            it.targets().requireSizeInOneToTen("targets")
        }
}

/**
 * [RemoveTargetsRequest]의 SDK builder 필드만 사용해 구성합니다.
 * 필수 rule·target ID 목록과 1..10 limit 검증이 필요한 경우 [removeTargetsRequestOf]를 사용하세요.
 */
inline fun removeTargetsRequest(
    builder: RemoveTargetsRequest.Builder.() -> Unit,
): RemoveTargetsRequest = RemoveTargetsRequest.builder()
    .apply(builder)
    .build()

/**
 * EventBridge의 요청당 대상 id 10개 제한에 맞춰 [RemoveTargetsRequest]를 구성합니다.
 *
 * 원본 응답에는 실패 항목 수와 상세 정보가 있으므로 호출자가 이를 확인해야 합니다.
 */
inline fun removeTargetsRequestOf(
    rule: String,
    ids: List<String>,
    eventBusName: String? = null,
    force: Boolean? = null,
    builder: RemoveTargetsRequest.Builder.() -> Unit = {},
): RemoveTargetsRequest {
    rule.requireNotBlank("rule")
    eventBusName?.requireNotBlank("eventBusName")
    ids.requireSizeInOneToTen("ids")
    ids.requireNoBlankValues("ids")

    return RemoveTargetsRequest.builder()
        .rule(rule)
        .ids(ids)
        .also {
            eventBusName?.let(it::eventBusName)
            force?.let(it::force)
        }
        .apply(builder)
        .build()
        .also { request ->
            request.rule().requireNotBlank("rule")
            request.eventBusName()?.requireNotBlank("eventBusName")
            request.ids().requireSizeInOneToTen("ids")
            request.ids().requireNoBlankValues("ids")
        }
}

/**
 * [PutEventsRequestEntry]의 SDK builder 필드만 사용해 구성합니다.
 * 필수 source/detail 검증이 필요한 경우 [putEventsRequestEntryOf]를 사용하세요.
 */
inline fun putEventsRequestEntry(
    builder: PutEventsRequestEntry.Builder.() -> Unit,
): PutEventsRequestEntry = PutEventsRequestEntry.builder()
    .apply(builder)
    .build()

/**
 * 사용자 정의 이벤트용 [PutEventsRequestEntry]를 구성합니다.
 *
 * [detail]은 비어 있지 않은 JSON 문자열이어야 합니다. 이 도우미는 임의 객체를 직렬화하지 않으며
 * AWS 요청 항목의 1 MB 제한도 계산하지 않습니다.
 */
inline fun putEventsRequestEntryOf(
    source: String,
    detailType: String,
    detail: String,
    eventBusName: String? = null,
    resources: List<String> = emptyList(),
    time: Instant? = null,
    traceHeader: String? = null,
    builder: PutEventsRequestEntry.Builder.() -> Unit = {},
): PutEventsRequestEntry {
    source.requireNotBlank("source")
    detailType.requireNotBlank("detailType")
    detail.requireNotBlank("detail")
    eventBusName?.requireNotBlank("eventBusName")
    resources.requireNoBlankValues("resources")
    traceHeader?.requireNotBlank("traceHeader")

    return PutEventsRequestEntry.builder()
        .source(source)
        .detailType(detailType)
        .detail(detail)
        .also {
            eventBusName?.let(it::eventBusName)
            if (resources.isNotEmpty()) it.resources(resources)
            time?.let(it::time)
            traceHeader?.let(it::traceHeader)
        }
        .apply(builder)
        .build()
        .also { request ->
            request.source().requireNotBlank("source")
            request.detailType().requireNotBlank("detailType")
            request.detail().requireNotBlank("detail")
            request.eventBusName()?.requireNotBlank("eventBusName")
            request.resources().requireNoBlankValues("resources")
            request.traceHeader()?.requireNotBlank("traceHeader")
        }
}

/**
 * [PutEventsRequest]의 SDK builder 필드만 사용해 구성합니다.
 * 필수 entry 목록과 1..10 limit 검증이 필요한 경우 [putEventsRequestOf]를 사용하세요.
 */
inline fun putEventsRequest(
    builder: PutEventsRequest.Builder.() -> Unit,
): PutEventsRequest = PutEventsRequest.builder()
    .apply(builder)
    .build()

/**
 * EventBridge의 요청당 항목 10개 제한에 맞춰 [PutEventsRequest]를 구성합니다.
 *
 * 원본 응답에는 실패 항목 수와 상세 정보가 있으므로 호출자가 이를 확인해야 합니다.
 */
inline fun putEventsRequestOf(
    entries: List<PutEventsRequestEntry>,
    builder: PutEventsRequest.Builder.() -> Unit = {},
): PutEventsRequest {
    entries.requireSizeInOneToTen("entries")
    return PutEventsRequest.builder()
        .entries(entries)
        .apply(builder)
        .build()
        .also { it.entries().requireSizeInOneToTen("entries") }
}

/**
 * [ListRulesRequest]의 SDK builder 필드만 사용해 구성합니다.
 * list field와 1..100 limit 검증이 필요한 경우 [listRulesRequestOf]를 사용하세요.
 */
inline fun listRulesRequest(
    builder: ListRulesRequest.Builder.() -> Unit,
): ListRulesRequest = ListRulesRequest.builder()
    .apply(builder)
    .build()

/**
 * [ListRulesRequest]를 구성합니다.
 *
 * 요청을 구성하기 전에 [limit]이 EventBridge의 목록 제한인 1..100 범위인지 검증합니다.
 */
inline fun listRulesRequestOf(
    eventBusName: String? = null,
    namePrefix: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
    builder: ListRulesRequest.Builder.() -> Unit = {},
): ListRulesRequest {
    eventBusName?.requireNotBlank("eventBusName")
    namePrefix?.requireNotBlank("namePrefix")
    limit?.requireEventBridgeListLimit("limit")
    nextToken?.requireNotBlank("nextToken")
    return ListRulesRequest.builder()
        .also {
            eventBusName?.let(it::eventBusName)
            namePrefix?.let(it::namePrefix)
            limit?.let(it::limit)
            nextToken?.let(it::nextToken)
        }
        .apply(builder)
        .build()
        .also { request ->
            request.eventBusName()?.requireNotBlank("eventBusName")
            request.namePrefix()?.requireNotBlank("namePrefix")
            request.limit()?.requireEventBridgeListLimit("limit")
            request.nextToken()?.requireNotBlank("nextToken")
        }
}

/**
 * [ListTargetsByRuleRequest]의 SDK builder 필드만 사용해 구성합니다.
 * 필수 rule과 1..100 limit 검증이 필요한 경우 [listTargetsByRuleRequestOf]를 사용하세요.
 */
inline fun listTargetsByRuleRequest(
    builder: ListTargetsByRuleRequest.Builder.() -> Unit,
): ListTargetsByRuleRequest = ListTargetsByRuleRequest.builder()
    .apply(builder)
    .build()

/**
 * [ListTargetsByRuleRequest]를 구성합니다.
 *
 * 요청을 구성하기 전에 [limit]이 EventBridge의 목록 제한인 1..100 범위인지 검증합니다.
 */
inline fun listTargetsByRuleRequestOf(
    rule: String,
    eventBusName: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
    builder: ListTargetsByRuleRequest.Builder.() -> Unit = {},
): ListTargetsByRuleRequest {
    rule.requireNotBlank("rule")
    eventBusName?.requireNotBlank("eventBusName")
    limit?.requireEventBridgeListLimit("limit")
    nextToken?.requireNotBlank("nextToken")
    return ListTargetsByRuleRequest.builder()
        .rule(rule)
        .also {
            eventBusName?.let(it::eventBusName)
            limit?.let(it::limit)
            nextToken?.let(it::nextToken)
        }
        .apply(builder)
        .build()
        .also { request ->
            request.rule().requireNotBlank("rule")
            request.eventBusName()?.requireNotBlank("eventBusName")
            request.limit()?.requireEventBridgeListLimit("limit")
            request.nextToken()?.requireNotBlank("nextToken")
        }
}
