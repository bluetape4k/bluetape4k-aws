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
 * Coroutine-oriented EventBridge operations for Spring applications.
 *
 * ## Contract
 *
 * Methods keep raw AWS SDK response types so callers can inspect partial
 * failures from `PutEvents`, `PutTargets`, and `RemoveTargets`. One method call
 * performs one SDK request; callers own batching, retries, and cleanup order.
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
