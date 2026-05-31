package io.bluetape4k.aws.examples.ktor.sqs

import io.bluetape4k.aws.ktor.sqs.SqsConsumer
import io.bluetape4k.aws.ktor.sqs.SqsConsumerInterceptor
import io.bluetape4k.aws.ktor.sqs.SqsConsumerObservation
import io.bluetape4k.aws.ktor.sqs.SqsConversionFailurePolicy
import io.bluetape4k.aws.ktor.sqs.SqsFixedFailureVisibilityStrategy
import io.bluetape4k.aws.ktor.sqs.SqsMessageContext
import io.bluetape4k.aws.ktor.sqs.sqsConsumer
import io.bluetape4k.ktor.core.requiredPathParameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ktor SQS consumer example module.
 *
 * ## Behavior / Contract
 *
 * Installs [SqsConsumer] with manual acknowledgement enabled, exposes routes for
 * publishing and queue management, and records consumer lifecycle events so
 * example clients can inspect ack, nack, retry, interceptor, and observer flows.
 */
fun Application.sqsExampleModule(
    sqsClient: SqsAsyncClient,
    queueUrl: String,
) {
    val received = CopyOnWriteArrayList<String>()
    val lifecycleEvents = CopyOnWriteArrayList<SqsLifecycleEvent>()
    val observations = CopyOnWriteArrayList<SqsObservationSummary>()
    val retriedMessageIds = ConcurrentHashMap.newKeySet<String>()

    install(ContentNegotiation) { jackson() }

    install(SqsConsumer) {
        sqsAsyncClient = sqsClient
        this.queueUrl = queueUrl
        coroutines = 2
        maxMessages = 10
        waitTimeSeconds = 1
        visibilityTimeoutSeconds = 30
        deleteOnSuccess = false
        conversionFailurePolicy = SqsConversionFailurePolicy.HandleAsFailure
        failureVisibilityStrategy = SqsFixedFailureVisibilityStrategy(timeoutSeconds = 5)

        interceptor(object: SqsConsumerInterceptor {
            override suspend fun afterReceive(queueUrl: String, messages: List<Message>) {
                if (messages.isNotEmpty()) {
                    lifecycleEvents += SqsLifecycleEvent("afterReceive", messages.size.toString())
                }
            }

            override suspend fun beforeInvoke(context: SqsMessageContext) {
                lifecycleEvents += SqsLifecycleEvent("beforeInvoke", context.message.messageId())
            }

            override suspend fun afterInvoke(context: SqsMessageContext) {
                lifecycleEvents += SqsLifecycleEvent("afterInvoke", context.message.messageId())
            }

            override suspend fun beforeAck(context: SqsMessageContext) {
                lifecycleEvents += SqsLifecycleEvent("beforeAck", context.message.messageId())
            }

            override suspend fun afterAck(context: SqsMessageContext) {
                lifecycleEvents += SqsLifecycleEvent("afterAck", context.message.messageId())
            }

            override suspend fun beforeNack(context: SqsMessageContext, timeoutSeconds: Int) {
                lifecycleEvents += SqsLifecycleEvent("beforeNack", context.message.messageId())
            }

            override suspend fun afterNack(context: SqsMessageContext, timeoutSeconds: Int) {
                lifecycleEvents += SqsLifecycleEvent("afterNack", context.message.messageId())
            }
        })

        observer { observation: SqsConsumerObservation ->
            observations += observation.toSummary()
        }

        onMessage<String> { body ->
            val messageId = message.messageId().orEmpty()
            if (body.startsWith(RETRY_ONCE_PREFIX) && retriedMessageIds.add(messageId)) {
                lifecycleEvents += SqsLifecycleEvent("retryOnce", messageId)
                nack(timeoutSeconds = 0)
                return@onMessage
            }

            received.add(body)
            ack()
        }
    }

    routing {
        post("/sqs/messages") {
            val body = call.receiveText()
            val response = call.application.sqsConsumer().send(body, queueUrl)
            call.respondText("""{"messageId":"${response.messageId()}"}""")
        }

        get("/sqs/messages/received") {
            call.respond(received.toList())
        }

        get("/sqs/messages/lifecycle-events") {
            call.respond(lifecycleEvents.toList())
        }

        get("/sqs/messages/observations") {
            call.respond(observations.toList())
        }

        post("/sqs/queues/{name}") {
            val name = call.requiredPathParameter("name")
            val url = sqsClient.createQueue { it.queueName(name) }.await().queueUrl()
            call.respondText("""{"queueUrl":"$url"}""")
        }

        delete("/sqs/queues") {
            val url = call.request.queryParameters["url"] ?: queueUrl
            sqsClient.deleteQueue { it.queueUrl(url) }.await()
            call.respond(HttpStatusCode.NoContent)
        }

        get("/sqs/queues/attributes") {
            val url = call.request.queryParameters["url"] ?: queueUrl
            val attrs = sqsClient.getQueueAttributes {
                it.queueUrl(url).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
            }.await().attributes()
            val count = attrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES] ?: "0"
            call.respondText("""{"approximateMessageCount":$count}""")
        }
    }
}

private const val RETRY_ONCE_PREFIX = "retry-once:"

private data class SqsLifecycleEvent(
    val event: String,
    val value: String?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private data class SqsObservationSummary(
    val operation: String,
    val outcome: String,
    val messageId: String?,
    val durationMs: Long?,
    val tags: Map<String, String>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private fun SqsConsumerObservation.toSummary(): SqsObservationSummary =
    SqsObservationSummary(
        operation = operation,
        outcome = outcome,
        messageId = messageId,
        durationMs = duration?.toMillis(),
        tags = tags,
    )
