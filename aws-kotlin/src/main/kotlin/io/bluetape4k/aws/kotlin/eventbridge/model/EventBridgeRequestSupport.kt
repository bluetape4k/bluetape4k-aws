package io.bluetape4k.aws.kotlin.eventbridge.model

import aws.sdk.kotlin.services.eventbridge.model.CreateEventBusRequest
import aws.sdk.kotlin.services.eventbridge.model.DeleteEventBusRequest
import aws.sdk.kotlin.services.eventbridge.model.DeleteRuleRequest
import aws.sdk.kotlin.services.eventbridge.model.ListRulesRequest
import aws.sdk.kotlin.services.eventbridge.model.ListTargetsByRuleRequest
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequest
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequestEntry
import aws.sdk.kotlin.services.eventbridge.model.PutRuleRequest
import aws.sdk.kotlin.services.eventbridge.model.PutTargetsRequest
import aws.sdk.kotlin.services.eventbridge.model.RemoveTargetsRequest
import aws.sdk.kotlin.services.eventbridge.model.RuleState
import aws.sdk.kotlin.services.eventbridge.model.Target
import aws.smithy.kotlin.runtime.time.Instant
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

@PublishedApi
internal const val MAX_EVENTBRIDGE_BATCH_SIZE = 10

@PublishedApi
internal fun <T> List<T>.requireSizeInOneToTen(name: String) {
    requireNotEmpty(name)
    size.requireInRange(1, MAX_EVENTBRIDGE_BATCH_SIZE, "$name size")
}

@PublishedApi
internal fun Iterable<String>.requireNoBlankValues(name: String) {
    forEach { it.requireNotBlank(name) }
}

/** 버스 이름으로 [CreateEventBusRequest]를 구성합니다. */
inline fun createEventBusRequestOf(
    name: String,
    crossinline builder: CreateEventBusRequest.Builder.() -> Unit = {},
): CreateEventBusRequest {
    name.requireNotBlank("name")
    return CreateEventBusRequest {
        this.name = name
        builder()
    }
}

/** 버스 이름으로 [DeleteEventBusRequest]를 구성합니다. */
inline fun deleteEventBusRequestOf(
    name: String,
    crossinline builder: DeleteEventBusRequest.Builder.() -> Unit = {},
): DeleteEventBusRequest {
    name.requireNotBlank("name")
    return DeleteEventBusRequest {
        this.name = name
        builder()
    }
}

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
    crossinline builder: PutRuleRequest.Builder.() -> Unit = {},
): PutRuleRequest {
    name.requireNotBlank("name")
    eventBusName?.requireNotBlank("eventBusName")
    eventPattern?.requireNotBlank("eventPattern")
    scheduleExpression?.requireNotBlank("scheduleExpression")
    require(!eventPattern.isNullOrBlank() || !scheduleExpression.isNullOrBlank()) {
        "eventPattern or scheduleExpression must be provided."
    }

    return PutRuleRequest {
        this.name = name
        this.eventBusName = eventBusName
        this.eventPattern = eventPattern
        this.scheduleExpression = scheduleExpression
        this.state = state
        this.description = description
        builder()
    }
}

/** [DeleteRuleRequest]를 구성합니다. 규칙을 삭제하기 전에 대상을 제거하세요. */
inline fun deleteRuleRequestOf(
    name: String,
    eventBusName: String? = null,
    force: Boolean? = null,
    crossinline builder: DeleteRuleRequest.Builder.() -> Unit = {},
): DeleteRuleRequest {
    name.requireNotBlank("name")
    eventBusName?.requireNotBlank("eventBusName")
    return DeleteRuleRequest {
        this.name = name
        this.eventBusName = eventBusName
        this.force = force
        builder()
    }
}

/** 필수 id와 ARN으로 [Target]을 구성합니다. */
inline fun targetOf(
    id: String,
    arn: String,
    crossinline builder: Target.Builder.() -> Unit = {},
): Target {
    id.requireNotBlank("id")
    arn.requireNotBlank("arn")
    return Target {
        this.id = id
        this.arn = arn
        builder()
    }
}

/** EventBridge의 요청당 대상 10개 제한에 맞춰 [PutTargetsRequest]를 구성합니다. */
inline fun putTargetsRequestOf(
    rule: String,
    targets: List<Target>,
    eventBusName: String? = null,
    crossinline builder: PutTargetsRequest.Builder.() -> Unit = {},
): PutTargetsRequest {
    rule.requireNotBlank("rule")
    eventBusName?.requireNotBlank("eventBusName")
    targets.requireSizeInOneToTen("targets")

    return PutTargetsRequest {
        this.rule = rule
        this.targets = targets
        this.eventBusName = eventBusName
        builder()
    }
}

/** EventBridge의 요청당 대상 id 10개 제한에 맞춰 [RemoveTargetsRequest]를 구성합니다. */
inline fun removeTargetsRequestOf(
    rule: String,
    ids: List<String>,
    eventBusName: String? = null,
    force: Boolean? = null,
    crossinline builder: RemoveTargetsRequest.Builder.() -> Unit = {},
): RemoveTargetsRequest {
    rule.requireNotBlank("rule")
    eventBusName?.requireNotBlank("eventBusName")
    ids.requireSizeInOneToTen("ids")
    ids.requireNoBlankValues("ids")

    return RemoveTargetsRequest {
        this.rule = rule
        this.ids = ids
        this.eventBusName = eventBusName
        this.force = force
        builder()
    }
}

/**
 * [PutEventsRequestEntry]를 구성합니다.
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
    crossinline builder: PutEventsRequestEntry.Builder.() -> Unit = {},
): PutEventsRequestEntry {
    source.requireNotBlank("source")
    detailType.requireNotBlank("detailType")
    detail.requireNotBlank("detail")
    eventBusName?.requireNotBlank("eventBusName")
    resources.requireNoBlankValues("resources")
    traceHeader?.requireNotBlank("traceHeader")

    return PutEventsRequestEntry {
        this.source = source
        this.detailType = detailType
        this.detail = detail
        this.eventBusName = eventBusName
        if (resources.isNotEmpty()) {
            this.resources = resources
        }
        this.time = time
        this.traceHeader = traceHeader
        builder()
    }
}

/** EventBridge의 요청당 항목 10개 제한에 맞춰 [PutEventsRequest]를 구성합니다. */
inline fun putEventsRequestOf(
    entries: List<PutEventsRequestEntry>,
    crossinline builder: PutEventsRequest.Builder.() -> Unit = {},
): PutEventsRequest {
    entries.requireSizeInOneToTen("entries")
    return PutEventsRequest {
        this.entries = entries
        builder()
    }
}

/** [ListRulesRequest]를 구성합니다. */
inline fun listRulesRequestOf(
    eventBusName: String? = null,
    namePrefix: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
    crossinline builder: ListRulesRequest.Builder.() -> Unit = {},
): ListRulesRequest {
    eventBusName?.requireNotBlank("eventBusName")
    namePrefix?.requireNotBlank("namePrefix")
    nextToken?.requireNotBlank("nextToken")
    return ListRulesRequest {
        this.eventBusName = eventBusName
        this.namePrefix = namePrefix
        this.limit = limit
        this.nextToken = nextToken
        builder()
    }
}

/** [ListTargetsByRuleRequest]를 구성합니다. */
inline fun listTargetsByRuleRequestOf(
    rule: String,
    eventBusName: String? = null,
    limit: Int? = null,
    nextToken: String? = null,
    crossinline builder: ListTargetsByRuleRequest.Builder.() -> Unit = {},
): ListTargetsByRuleRequest {
    rule.requireNotBlank("rule")
    eventBusName?.requireNotBlank("eventBusName")
    nextToken?.requireNotBlank("nextToken")
    return ListTargetsByRuleRequest {
        this.rule = rule
        this.eventBusName = eventBusName
        this.limit = limit
        this.nextToken = nextToken
        builder()
    }
}
