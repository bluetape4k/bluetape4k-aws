package io.bluetape4k.aws.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.aws.sqs.model.sendMessageBatchRequestEntry
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.trace
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import software.amazon.awssdk.services.sqs.model.DeleteQueueResponse

@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SqsAsyncClientTest: AbstractSqsTest() {

    companion object: KLoggingChannel() {
        private const val QUEUE_PREFIX = "coroutines-queue"
        private val QUEUE_NAME = "$QUEUE_PREFIX-${Base58.randomString(12).lowercase()}"
    }

    private lateinit var queueUrl: String

    @Test
    @Order(1)
    fun `create queue`() = runSuspendIO {
        val url = asyncClient.createQueue(QUEUE_NAME)
        queueUrl = asyncClient.getQueueUrl(QUEUE_NAME).queueUrl()

        log.debug { "queue url=$queueUrl" }
        queueUrl shouldBeEqualTo url
    }

    @Test
    @Order(2)
    fun `list queues`() = runSuspendIO {
        val response = asyncClient.listQueuesSuspend(QUEUE_PREFIX)

        response.queueUrls().forEach { log.debug { "queue url=$it" } }
        response.queueUrls() shouldHaveSize 1
    }

    @Test
    @Order(3)
    fun `send message`() = runSuspendIO {
        val response = asyncClient.send(queueUrl, "Hello, World!")

        log.debug { "response=$response" }
        response.messageId().shouldNotBeEmpty()
    }

    @Test
    @Order(4)
    fun `send messages in batch mode`() = runSuspendIO {
        // NOTE: The total size of all messages that you send in a single SendMessageBatch call can't exceed 262,144 bytes (256 KB).
        // https://stackoverflow.com/questions/40489815/checking-size-of-sqs-message-batches
        // 이 것 계산하려면 Jdk Serializer를 통해서 bytes 를 계산해야 한다
        val entries = List(10) { index ->
            sendMessageBatchRequestEntry {
                id("id-$index")
                messageBody("Hello, world $index")
            }
        }
        val response = asyncClient.sendBatch(queueUrl, entries)

        response.successful().forEach { log.debug { "result entry=$it" } }
        response.successful() shouldHaveSize entries.size
    }

    @Test
    @Order(5)
    fun `receive messages`() = runSuspendIO {
        val messages = asyncClient.receiveMessages(queueUrl, 3).messages()

        messages.forEach { log.trace { "message=$it" } }
        messages shouldHaveSize 3
    }

    @Test
    @Order(6)
    fun `change messages`() = runSuspendIO {
        val messages = asyncClient
            .receiveMessages(queueUrl, 3)
            .messages()

        val responses = messages.map { message ->
            asyncClient.changeMessageVisibility(queueUrl, message.receiptHandle(), 10)
            client.changeMessageVisibility {
                it.queueUrl(queueUrl).receiptHandle(message.receiptHandle()).visibilityTimeout(10)
            }
        }

        responses.forEach { log.debug { "response  metadata=${it.responseMetadata()}" } }
        responses shouldHaveSize messages.size
    }

    @Test
    @Order(7)
    fun `delete messages`() = runSuspendIO {
        val messages = asyncClient
            .receiveMessages(queueUrl, 3)
            .messages()

        val responses = messages.map { message ->
            asyncClient.deleteMessage(queueUrl, message.receiptHandle())
        }

        responses.forEach { log.debug { "response=$it" } }
        responses shouldHaveSize messages.size
    }

    @Test
    @Order(8)
    fun `delete queue`() = runSuspendIO {
        val response: DeleteQueueResponse = asyncClient.deleteQueue(queueUrl)

        log.debug { "delete queue response=$response" }
        response.responseMetadata().requestId().shouldNotBeEmpty()
    }
}
