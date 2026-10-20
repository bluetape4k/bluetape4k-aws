package io.bluetape4k.aws.examples.ktor.sqs

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.getQueueAttributes
import aws.sdk.kotlin.services.sqs.model.Message
import aws.sdk.kotlin.services.sqs.model.QueueAttributeName
import io.bluetape4k.aws.ktor.sqs.SqsConsumer
import io.bluetape4k.aws.ktor.sqs.SqsConsumerInterceptor
import io.bluetape4k.aws.ktor.sqs.SqsConsumerObservation
import io.bluetape4k.aws.ktor.sqs.SqsConversionFailurePolicy
import io.bluetape4k.aws.ktor.sqs.SqsFixedFailureVisibilityStrategy
import io.bluetape4k.aws.ktor.sqs.SqsMessageContext
import io.bluetape4k.aws.ktor.sqs.sqsConsumer
import io.bluetape4k.aws.kotlin.sqs.createQueue
import io.bluetape4k.aws.kotlin.sqs.deleteQueue
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
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ktor SQS consumer 예제 모듈입니다.
 *
 * ## 동작/계약
 *
 * 수동 acknowledgement를 활성화한 [SqsConsumer]를 설치하고 publish/queue 관리 route를
 * 노출합니다. consumer lifecycle event를 기록하므로 예제 client가 ack, nack, retry,
 * interceptor와 observer 흐름을 확인할 수 있습니다.
 */
fun Application.sqsExampleModule(
    sqsClient: SqsClient,
    queueUrl: String,
) {
    val received = CopyOnWriteArrayList<String>()
    val lifecycleEvents = CopyOnWriteArrayList<SqsLifecycleEvent>()
    val observations = CopyOnWriteArrayList<SqsObservationSummary>()
    val retriedMessageIds = ConcurrentHashMap.newKeySet<String>()

    install(ContentNegotiation) { jackson() }

    install(SqsConsumer) {
        this.sqsClient = sqsClient
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
                lifecycleEvents += SqsLifecycleEvent("beforeInvoke", context.message.messageId.orEmpty())
            }

            override suspend fun afterInvoke(context: SqsMessageContext) {
                lifecycleEvents += SqsLifecycleEvent("afterInvoke", context.message.messageId.orEmpty())
            }

            override suspend fun beforeAck(context: SqsMessageContext) {
                lifecycleEvents += SqsLifecycleEvent("beforeAck", context.message.messageId.orEmpty())
            }

            override suspend fun afterAck(context: SqsMessageContext) {
                lifecycleEvents += SqsLifecycleEvent("afterAck", context.message.messageId.orEmpty())
            }

            override suspend fun beforeNack(context: SqsMessageContext, timeoutSeconds: Int) {
                lifecycleEvents += SqsLifecycleEvent("beforeNack", context.message.messageId.orEmpty())
            }

            override suspend fun afterNack(context: SqsMessageContext, timeoutSeconds: Int) {
                lifecycleEvents += SqsLifecycleEvent("afterNack", context.message.messageId.orEmpty())
            }
        })

        observer { observation: SqsConsumerObservation ->
            observations += observation.toSummary()
        }

        onMessage<String> { body ->
            val messageId = message.messageId.orEmpty()
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
            call.respondText("""{"messageId":"${response.messageId.orEmpty()}"}""")
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
            val url = sqsClient.createQueue(name).queueUrl.orEmpty()
            call.respondText("""{"queueUrl":"$url"}""")
        }

        delete("/sqs/queues") {
            val url = call.request.queryParameters["url"] ?: queueUrl
            sqsClient.deleteQueue(url)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/sqs/queues/attributes") {
            val url = call.request.queryParameters["url"] ?: queueUrl
            val attrs = sqsClient.getQueueAttributes {
                this.queueUrl = url
                this.attributeNames = listOf(QueueAttributeName.ApproximateNumberOfMessages)
            }.attributes
            val count = attrs?.get(QueueAttributeName.ApproximateNumberOfMessages) ?: "0"
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
