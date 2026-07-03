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
 * Builds [CreateEventBusRequest] from a bus name.
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
}

/**
 * Builds [DeleteEventBusRequest] from a bus name.
 *
 * Delete rules before deleting a custom or partner event bus.
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
}

/**
 * Builds [PutRuleRequest].
 *
 * EventBridge requires either [eventPattern] or [scheduleExpression]. This
 * helper does not merge omitted fields with an existing rule.
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
        .also { eventBusName?.let(it::eventBusName) }
        .also { eventPattern?.let(it::eventPattern) }
        .also { scheduleExpression?.let(it::scheduleExpression) }
        .also { state?.let(it::state) }
        .also { description?.let(it::description) }
        .apply(builder)
        .build()
}

/**
 * Builds [DeleteRuleRequest].
 *
 * Remove targets and inspect any failed removals before deleting a rule.
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
        .also { eventBusName?.let(it::eventBusName) }
        .also { force?.let(it::force) }
        .apply(builder)
        .build()
}

/**
 * Builds [Target] with required id and ARN.
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
 * Builds [PutTargetsRequest] with EventBridge's 10-target request limit.
 *
 * The raw response contains failed-entry counts/details; callers must inspect it.
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
}

/**
 * Builds [RemoveTargetsRequest] with EventBridge's 10-target-id request limit.
 *
 * The raw response contains failed-entry counts/details; callers must inspect it.
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
        .also { eventBusName?.let(it::eventBusName) }
        .also { force?.let(it::force) }
        .apply(builder)
        .build()
}

/**
 * Builds [PutEventsRequestEntry] for a custom event.
 *
 * [detail] must be a nonblank JSON string. This helper does not serialize
 * arbitrary objects and does not estimate the AWS 1 MB request-entry limit.
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
        .also { eventBusName?.let(it::eventBusName) }
        .also { if (resources.isNotEmpty()) it.resources(resources) }
        .also { time?.let(it::time) }
        .also { traceHeader?.let(it::traceHeader) }
        .apply(builder)
        .build()
}

/**
 * Builds [PutEventsRequest] with EventBridge's 10-entry request limit.
 *
 * The raw response contains failed-entry counts/details; callers must inspect it.
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
}

/**
 * Builds [ListRulesRequest].
 *
 * [limit] is validated against EventBridge's 1..100 list limit before the
 * request is built.
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
        .also { eventBusName?.let(it::eventBusName) }
        .also { namePrefix?.let(it::namePrefix) }
        .also { limit?.let(it::limit) }
        .also { nextToken?.let(it::nextToken) }
        .apply(builder)
        .build()
}

/**
 * Builds [ListTargetsByRuleRequest].
 *
 * [limit] is validated against EventBridge's 1..100 list limit before the
 * request is built.
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
        .also { eventBusName?.let(it::eventBusName) }
        .also { limit?.let(it::limit) }
        .also { nextToken?.let(it::nextToken) }
        .apply(builder)
        .build()
}
