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
 * Coroutine and future-friendly EventBridge operations for Ktor applications.
 *
 * ## Contract
 *
 * Methods keep raw AWS SDK responses, including partial-failure metadata from
 * `PutEvents`, `PutTargets`, and `RemoveTargets`. One call maps to one SDK
 * request; batching, retry, and cleanup order remain caller responsibilities.
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
