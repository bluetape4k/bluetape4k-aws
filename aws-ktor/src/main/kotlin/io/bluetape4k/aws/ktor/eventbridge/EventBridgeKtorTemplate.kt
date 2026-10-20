package io.bluetape4k.aws.ktor.eventbridge

import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
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
import io.bluetape4k.aws.kotlin.eventbridge.createEventBus as createEventBusSuspend
import io.bluetape4k.aws.kotlin.eventbridge.deleteEventBus as deleteEventBusSuspend
import io.bluetape4k.aws.kotlin.eventbridge.deleteRule as deleteRuleSuspend
import io.bluetape4k.aws.kotlin.eventbridge.listRules as listRulesSuspend
import io.bluetape4k.aws.kotlin.eventbridge.listTargetsByRule as listTargetsByRuleSuspend
import io.bluetape4k.aws.kotlin.eventbridge.putEvents as putEventsSuspend
import io.bluetape4k.aws.kotlin.eventbridge.putRule as putRuleSuspend
import io.bluetape4k.aws.kotlin.eventbridge.putTargets as putTargetsSuspend
import io.bluetape4k.aws.kotlin.eventbridge.removeTargets as removeTargetsSuspend

/**
 * AWS Kotlin SDK [EventBridgeClient]를 사용하는 기본 [EventBridgeKtorOperations] 구현입니다.
 *
 * 선택적 이벤트 버스 이름은 각 호출에서 명시한 이름이 우선하고, 생략된 경우에만 기본값을 사용합니다.
 */
class EventBridgeKtorTemplate(
    private val eventBridgeClient: EventBridgeClient,
    private val defaultEventBusName: String? = null,
): EventBridgeKtorOperations {

    override suspend fun createEventBus(name: String): CreateEventBusResponse =
        eventBridgeClient.createEventBusSuspend(name)

    override suspend fun deleteEventBus(name: String): DeleteEventBusResponse =
        eventBridgeClient.deleteEventBusSuspend(name)

    override suspend fun putRule(
        name: String,
        eventBusName: String?,
        eventPattern: String?,
        scheduleExpression: String?,
        state: RuleState?,
        description: String?,
    ): PutRuleResponse =
        eventBridgeClient.putRuleSuspend(
            name = name,
            eventBusName = resolveEventBusName(eventBusName),
            eventPattern = eventPattern,
            scheduleExpression = scheduleExpression,
            state = state,
            description = description,
        )

    override suspend fun deleteRule(
        name: String,
        eventBusName: String?,
        force: Boolean?,
    ): DeleteRuleResponse =
        eventBridgeClient.deleteRuleSuspend(name, resolveEventBusName(eventBusName), force)

    override suspend fun putTargets(
        rule: String,
        targets: List<Target>,
        eventBusName: String?,
    ): PutTargetsResponse =
        eventBridgeClient.putTargetsSuspend(rule, targets, resolveEventBusName(eventBusName))

    override suspend fun removeTargets(
        rule: String,
        ids: List<String>,
        eventBusName: String?,
        force: Boolean?,
    ): RemoveTargetsResponse =
        eventBridgeClient.removeTargetsSuspend(rule, ids, resolveEventBusName(eventBusName), force)

    override suspend fun listRules(
        eventBusName: String?,
        namePrefix: String?,
        limit: Int?,
        nextToken: String?,
    ): ListRulesResponse =
        eventBridgeClient.listRulesSuspend(
            eventBusName = resolveEventBusName(eventBusName),
            namePrefix = namePrefix,
            limit = limit,
            nextToken = nextToken,
        )

    override suspend fun listTargetsByRule(
        rule: String,
        eventBusName: String?,
        limit: Int?,
        nextToken: String?,
    ): ListTargetsByRuleResponse =
        eventBridgeClient.listTargetsByRuleSuspend(
            rule = rule,
            eventBusName = resolveEventBusName(eventBusName),
            limit = limit,
            nextToken = nextToken,
        )

    override suspend fun putEvents(entries: List<PutEventsRequestEntry>): PutEventsResponse =
        eventBridgeClient.putEventsSuspend(entries)

    private fun resolveEventBusName(eventBusName: String?): String? =
        eventBusName ?: defaultEventBusName
}
