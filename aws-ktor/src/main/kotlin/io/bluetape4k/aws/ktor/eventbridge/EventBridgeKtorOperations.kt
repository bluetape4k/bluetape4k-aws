package io.bluetape4k.aws.ktor.eventbridge

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

/**
 * Ktor 애플리케이션을 위한 suspend 기반 EventBridge 작업입니다.
 *
 * ## 계약
 *
 * `PutEvents`, `PutTargets`, `RemoveTargets`의 부분 실패 상세 필드를 포함한 AWS Kotlin SDK
 * 원본 응답을 유지합니다. 호출 한 번은 SDK 요청 하나에 대응하며 배치 처리, 재시도, 정리 순서는
 * 호출자가 책임집니다.
 */
interface EventBridgeKtorOperations {
    suspend fun createEventBus(name: String): CreateEventBusResponse

    suspend fun deleteEventBus(name: String): DeleteEventBusResponse

    suspend fun putRule(
        name: String,
        eventBusName: String? = null,
        eventPattern: String? = null,
        scheduleExpression: String? = null,
        state: RuleState? = null,
        description: String? = null,
    ): PutRuleResponse

    suspend fun deleteRule(
        name: String,
        eventBusName: String? = null,
        force: Boolean? = null,
    ): DeleteRuleResponse

    suspend fun putTargets(
        rule: String,
        targets: List<Target>,
        eventBusName: String? = null,
    ): PutTargetsResponse

    suspend fun removeTargets(
        rule: String,
        ids: List<String>,
        eventBusName: String? = null,
        force: Boolean? = null,
    ): RemoveTargetsResponse

    suspend fun listRules(
        eventBusName: String? = null,
        namePrefix: String? = null,
        limit: Int? = null,
        nextToken: String? = null,
    ): ListRulesResponse

    suspend fun listTargetsByRule(
        rule: String,
        eventBusName: String? = null,
        limit: Int? = null,
        nextToken: String? = null,
    ): ListTargetsByRuleResponse

    suspend fun putEvents(entries: List<PutEventsRequestEntry>): PutEventsResponse
}
