package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.aws.spring.sqs.SqsFullRequestOperations
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsSendRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

/**
 * 기존 [SqsOperations]에 Modulith envelope를 연결하는 내부 SQS publisher입니다.
 *
 * 전체 요청 필드를 보존하려면 [SqsFullRequestOperations] capability가 필요합니다. 큐 URL
 * 해석은 configured alias별로 single-flight하며 성공한 URL만 bounded cache에 보관합니다.
 */
@Suppress("TooGenericExceptionCaught")
internal class AwsModulithSqsTargetPublisher internal constructor(
    sqsOperations: SqsOperations,
    configuredAliases: Set<String>,
) : AwsModulithTargetPublisher {

    private val sqsOperations: SqsFullRequestOperations = sqsOperations as? SqsFullRequestOperations
        ?: throw AwsModulithConfigurationException()
    private val configuredAliases: Set<String> = configuredAliases.toSet()
    private val cacheLock = Mutex()
    private val queueUrlCache = LinkedHashMap<String, String>(configuredAliases.size.coerceAtLeast(1), 0.75F, true)
    private val queueUrlFlights = mutableMapOf<String, CompletableDeferred<String>>()

    init {
        require(this.configuredAliases.isNotEmpty()) { "configuredAliases must not be empty." }
    }

    private sealed interface QueueUrlResolution {
        data class Cached(val queueUrl: String) : QueueUrlResolution
        data class Flight(val deferred: CompletableDeferred<String>, val owner: Boolean) : QueueUrlResolution
    }

    override suspend fun publish(command: AwsModulithPublishCommand): AwsModulithPublishResult {
        currentCoroutineContext().ensureActive()
        val destination = requireAwsModulithDestinationName(command.destination)
        val routingKey = validateAwsModulithRoutingKey(destination, command.routingKey)
        requireConfiguredAlias(command.targetAlias)
        val queueUrl = resolveQueueUrl(command.targetAlias, destination)
        currentCoroutineContext().ensureActive()
        val response = sanitizeAwsModulithPublishCall {
            sqsOperations.send(
                SqsSendRequest(
                    queueUrl = queueUrl,
                    body = command.encoded.body,
                    messageGroupId = routingKey,
                    messageDeduplicationId = routingKey?.let { sha256(command.eventId) },
                    messageAttributes = command.encoded.messageAttributes.toAwsMessageAttributes(),
                ),
            )
        }
        return AwsModulithPublishResult(
            service = AwsModulithTargetService.SQS,
            targetAlias = command.targetAlias,
            providerMessageIdPresent = response.messageId() != null,
        )
    }

    private fun requireConfiguredAlias(alias: String) {
        require(alias in configuredAliases) {
            "targetAlias must be configured before publishing."
        }
    }

    private suspend fun resolveQueueUrl(alias: String, destination: String): String {
        val resolution = cacheLock.withLock {
            queueUrlCache[alias]?.let { QueueUrlResolution.Cached(it) }
                ?: queueUrlFlights[alias]?.let { QueueUrlResolution.Flight(it, owner = false) }
                ?: QueueUrlResolution.Flight(
                    CompletableDeferred<String>().also { queueUrlFlights[alias] = it },
                    owner = true,
                )
        }
        if (resolution is QueueUrlResolution.Cached) return resolution.queueUrl
        val flight = (resolution as QueueUrlResolution.Flight).deferred

        if (resolution.owner) {
            try {
                val queueUrl = sanitizeAwsModulithResolutionCall { sqsOperations.getQueueUrl(destination) }
                require(queueUrl.isNotBlank()) { "resolved queue URL must not be blank." }
                cacheLock.withLock {
                    queueUrlFlights.remove(alias, flight)
                    queueUrlCache[alias] = queueUrl
                    while (queueUrlCache.size > configuredAliases.size) {
                        queueUrlCache.remove(queueUrlCache.entries.first().key)
                    }
                }
                flight.complete(queueUrl)
            } catch (cancelled: CancellationException) {
                removeFlight(alias, flight)
                flight.cancel(cancelled)
                throw cancelled
            } catch (failure: Throwable) {
                removeFlight(alias, flight)
                flight.completeExceptionally(failure)
                throw failure
            }
        }
        return flight.await()
    }

    private suspend fun removeFlight(alias: String, flight: CompletableDeferred<String>) {
        withContext(NonCancellable) {
            cacheLock.withLock { queueUrlFlights.remove(alias, flight) }
        }
    }
}

private fun Map<String, String>.toAwsMessageAttributes(): Map<String, MessageAttributeValue> =
    mapValues { (_, value) ->
        MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(value)
            .build()
    }

private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { "%02x".format(it) }
