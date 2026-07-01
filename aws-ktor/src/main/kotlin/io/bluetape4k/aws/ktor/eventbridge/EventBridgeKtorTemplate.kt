package io.bluetape4k.aws.ktor.eventbridge

import io.bluetape4k.aws.eventbridge.createEventBusAsync
import io.bluetape4k.aws.eventbridge.deleteEventBusAsync
import io.bluetape4k.aws.eventbridge.deleteRuleAsync
import io.bluetape4k.aws.eventbridge.listRulesAsync
import io.bluetape4k.aws.eventbridge.listTargetsByRuleAsync
import io.bluetape4k.aws.eventbridge.putEventsAsync
import io.bluetape4k.aws.eventbridge.putRuleAsync
import io.bluetape4k.aws.eventbridge.putTargetsAsync
import io.bluetape4k.aws.eventbridge.removeTargetsAsync
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
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
 * Default [EventBridgeKtorOperations] implementation backed by [EventBridgeAsyncClient].
 *
 * ## Contract
 *
 * Provides suspend and future-returning variants over the same AWS SDK v2 async
 * client. Future variants return the SDK future directly and do not await it.
 */
class EventBridgeKtorTemplate(
    private val eventBridgeAsyncClient: EventBridgeAsyncClient,
    private val defaultEventBusName: String? = null,
) : EventBridgeKtorOperations {

    override suspend fun createEventBus(name: String): CreateEventBusResponse =
        createEventBusAsync(name).await()

    override fun createEventBusAsync(name: String): CompletableFuture<CreateEventBusResponse> =
        eventBridgeAsyncClient.createEventBusAsync(name)

    override suspend fun deleteEventBus(name: String): DeleteEventBusResponse =
        deleteEventBusAsync(name).await()

    override fun deleteEventBusAsync(name: String): CompletableFuture<DeleteEventBusResponse> =
        eventBridgeAsyncClient.deleteEventBusAsync(name)

    override suspend fun putRule(
        name: String,
        eventBusName: String?,
        eventPattern: String?,
        scheduleExpression: String?,
        state: RuleState?,
        description: String?,
    ): PutRuleResponse =
        putRuleAsync(name, eventBusName, eventPattern, scheduleExpression, state, description).await()

    override fun putRuleAsync(
        name: String,
        eventBusName: String?,
        eventPattern: String?,
        scheduleExpression: String?,
        state: RuleState?,
        description: String?,
    ): CompletableFuture<PutRuleResponse> =
        eventBridgeAsyncClient.putRuleAsync(
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
        deleteRuleAsync(name, eventBusName, force).await()

    override fun deleteRuleAsync(
        name: String,
        eventBusName: String?,
        force: Boolean?,
    ): CompletableFuture<DeleteRuleResponse> =
        eventBridgeAsyncClient.deleteRuleAsync(name, resolveEventBusName(eventBusName), force)

    override suspend fun putTargets(
        rule: String,
        targets: List<Target>,
        eventBusName: String?,
    ): PutTargetsResponse =
        putTargetsAsync(rule, targets, eventBusName).await()

    override fun putTargetsAsync(
        rule: String,
        targets: List<Target>,
        eventBusName: String?,
    ): CompletableFuture<PutTargetsResponse> =
        eventBridgeAsyncClient.putTargetsAsync(rule, targets, resolveEventBusName(eventBusName))

    override suspend fun removeTargets(
        rule: String,
        ids: List<String>,
        eventBusName: String?,
        force: Boolean?,
    ): RemoveTargetsResponse =
        removeTargetsAsync(rule, ids, eventBusName, force).await()

    override fun removeTargetsAsync(
        rule: String,
        ids: List<String>,
        eventBusName: String?,
        force: Boolean?,
    ): CompletableFuture<RemoveTargetsResponse> =
        eventBridgeAsyncClient.removeTargetsAsync(rule, ids, resolveEventBusName(eventBusName), force)

    override suspend fun listRules(
        eventBusName: String?,
        namePrefix: String?,
        limit: Int?,
        nextToken: String?,
    ): ListRulesResponse =
        listRulesAsync(eventBusName, namePrefix, limit, nextToken).await()

    override fun listRulesAsync(
        eventBusName: String?,
        namePrefix: String?,
        limit: Int?,
        nextToken: String?,
    ): CompletableFuture<ListRulesResponse> =
        eventBridgeAsyncClient.listRulesAsync(resolveEventBusName(eventBusName), namePrefix, limit, nextToken)

    override suspend fun listTargetsByRule(
        rule: String,
        eventBusName: String?,
        limit: Int?,
        nextToken: String?,
    ): ListTargetsByRuleResponse =
        listTargetsByRuleAsync(rule, eventBusName, limit, nextToken).await()

    override fun listTargetsByRuleAsync(
        rule: String,
        eventBusName: String?,
        limit: Int?,
        nextToken: String?,
    ): CompletableFuture<ListTargetsByRuleResponse> =
        eventBridgeAsyncClient.listTargetsByRuleAsync(rule, resolveEventBusName(eventBusName), limit, nextToken)

    override suspend fun putEvents(entries: List<PutEventsRequestEntry>): PutEventsResponse =
        putEventsAsync(entries).await()

    override fun putEventsAsync(entries: List<PutEventsRequestEntry>): CompletableFuture<PutEventsResponse> =
        eventBridgeAsyncClient.putEventsAsync(entries)

    private fun resolveEventBusName(eventBusName: String?): String? =
        eventBusName ?: defaultEventBusName
}
