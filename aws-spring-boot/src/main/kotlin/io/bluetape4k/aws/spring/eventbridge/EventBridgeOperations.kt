package io.bluetape4k.aws.spring.eventbridge

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
 * Spring 애플리케이션을 위한 코루틴 중심 EventBridge 작업입니다.
 *
 * ## 계약
 *
 * 메서드는 호출자가 `PutEvents`, `PutTargets`, `RemoveTargets`의 부분 실패를 확인할 수 있도록
 * 원본 AWS SDK 응답 타입을 유지합니다. 메서드 호출 한 번은 SDK 요청 하나를 수행하며
 * 배치 처리, 재시도, 정리 순서는 호출자가 책임집니다.
 *
 * ```kotlin
 * suspend fun publish(events: EventBridgeOperations) {
 *     events.putEvents(listOf(putEventsRequestEntryOf("orders", "order.created", "{}")))
 * }
 * ```
 */
interface EventBridgeOperations {
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
