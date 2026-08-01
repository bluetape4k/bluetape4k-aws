package io.bluetape4k.aws.ktor.eventbridge

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

/**
 * Ktor 애플리케이션을 위한 코루틴 및 Future 친화적인 EventBridge 작업입니다.
 *
 * ## 계약
 *
 * 메서드는 `PutEvents`, `PutTargets`, `RemoveTargets`의 부분 실패 메타데이터를 포함한
 * 원본 AWS SDK 응답을 유지합니다. 호출 한 번은 SDK 요청 하나에 대응하며 배치 처리,
 * 재시도, 정리 순서는 호출자가 책임집니다.
 */
interface EventBridgeKtorOperations {
    suspend fun createEventBus(name: String): CreateEventBusResponse
    fun createEventBusAsync(name: String): CompletableFuture<CreateEventBusResponse>

    suspend fun deleteEventBus(name: String): DeleteEventBusResponse
    fun deleteEventBusAsync(name: String): CompletableFuture<DeleteEventBusResponse>

    suspend fun putRule(
        name: String,
        eventBusName: String? = null,
        eventPattern: String? = null,
        scheduleExpression: String? = null,
        state: RuleState? = null,
        description: String? = null,
    ): PutRuleResponse

    fun putRuleAsync(
        name: String,
        eventBusName: String? = null,
        eventPattern: String? = null,
        scheduleExpression: String? = null,
        state: RuleState? = null,
        description: String? = null,
    ): CompletableFuture<PutRuleResponse>

    suspend fun deleteRule(
        name: String,
        eventBusName: String? = null,
        force: Boolean? = null,
    ): DeleteRuleResponse

    fun deleteRuleAsync(
        name: String,
        eventBusName: String? = null,
        force: Boolean? = null,
    ): CompletableFuture<DeleteRuleResponse>

    suspend fun putTargets(
        rule: String,
        targets: List<Target>,
        eventBusName: String? = null,
    ): PutTargetsResponse

    fun putTargetsAsync(
        rule: String,
        targets: List<Target>,
        eventBusName: String? = null,
    ): CompletableFuture<PutTargetsResponse>

    suspend fun removeTargets(
        rule: String,
        ids: List<String>,
        eventBusName: String? = null,
        force: Boolean? = null,
    ): RemoveTargetsResponse

    fun removeTargetsAsync(
        rule: String,
        ids: List<String>,
        eventBusName: String? = null,
        force: Boolean? = null,
    ): CompletableFuture<RemoveTargetsResponse>

    suspend fun listRules(
        eventBusName: String? = null,
        namePrefix: String? = null,
        limit: Int? = null,
        nextToken: String? = null,
    ): ListRulesResponse

    fun listRulesAsync(
        eventBusName: String? = null,
        namePrefix: String? = null,
        limit: Int? = null,
        nextToken: String? = null,
    ): CompletableFuture<ListRulesResponse>

    suspend fun listTargetsByRule(
        rule: String,
        eventBusName: String? = null,
        limit: Int? = null,
        nextToken: String? = null,
    ): ListTargetsByRuleResponse

    fun listTargetsByRuleAsync(
        rule: String,
        eventBusName: String? = null,
        limit: Int? = null,
        nextToken: String? = null,
    ): CompletableFuture<ListTargetsByRuleResponse>

    suspend fun putEvents(entries: List<PutEventsRequestEntry>): PutEventsResponse
    fun putEventsAsync(entries: List<PutEventsRequestEntry>): CompletableFuture<PutEventsResponse>
}
