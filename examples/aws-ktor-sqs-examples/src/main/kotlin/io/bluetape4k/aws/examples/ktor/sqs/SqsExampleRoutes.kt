package io.bluetape4k.aws.examples.ktor.sqs

import io.bluetape4k.aws.ktor.sqs.SqsConsumer
import io.bluetape4k.aws.ktor.sqs.sqsConsumer
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
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ktor SQS Consumer 예제 애플리케이션 모듈.
 *
 * [sqsClient]가 소유하는 SqsAsyncClient를 받아 [SqsConsumer] 플러그인을 설치하고
 * SQS 메시지 전송·수신·대기열 관리 REST 라우트를 등록합니다.
 */
fun Application.sqsExampleModule(
    sqsClient: SqsAsyncClient,
    queueUrl: String,
) {
    val received = CopyOnWriteArrayList<String>()

    install(ContentNegotiation) { jackson() }

    install(SqsConsumer) {
        sqsAsyncClient = sqsClient
        this.queueUrl = queueUrl
        coroutines = 2
        maxMessages = 10
        waitTimeSeconds = 1
        visibilityTimeoutSeconds = 30

        onMessage<String> { body ->
            received.add(body)
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

        post("/sqs/queues/{name}") {
            val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
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
