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
import io.bluetape4k.support.requireNotBlank

@PublishedApi
internal const val MAX_EVENTBRIDGE_BATCH_SIZE = 10

@PublishedApi
internal fun <T> List<T>.requireSizeInOneToTen(name: String) {
    require(isNotEmpty()) { "$name must not be empty." }
    require(size <= MAX_EVENTBRIDGE_BATCH_SIZE) { "$name must not contain more than $MAX_EVENTBRIDGE_BATCH_SIZE items." }
}

@PublishedApi
internal fun Iterable<String>.requireNoBlankValues(name: String) {
    forEach { it.requireNotBlank(name) }
}

/** Builds [CreateEventBusRequest] from a bus name. */
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

/** Builds [DeleteEventBusRequest] from a bus name. */
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

/** Builds [DeleteRuleRequest]. Remove targets before deleting a rule. */
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

/** Builds [Target] with required id and ARN. */
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

/** Builds [PutTargetsRequest] with EventBridge's 10-target request limit. */
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

/** Builds [RemoveTargetsRequest] with EventBridge's 10-target-id request limit. */
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
 * Builds [PutEventsRequestEntry].
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

/** Builds [PutEventsRequest] with EventBridge's 10-entry request limit. */
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

/** Builds [ListRulesRequest]. */
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

/** Builds [ListTargetsByRuleRequest]. */
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
