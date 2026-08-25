package io.bluetape4k.aws.examples.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

class SqsSnsExampleModelsTest {

    @Test
    fun `public SQS SNS models preserve values through Java serialization`() {
        val models = listOf<Serializable>(
            QueueResponse("orders", "https://sqs.example.test/orders", "arn:aws:sqs:region:account:orders"),
            SendQueueMessageRequest("payload", delaySeconds = 2),
            QueueSendResponse("https://sqs.example.test/orders", "message-id"),
            QueueMessageResponse("https://sqs.example.test/orders", "message-id", "payload", "receipt"),
            FanoutSetupRequest("topic", "orders"),
            FanoutSetupResponse("topic-arn", "queue-url", "queue-arn", "subscription-arn"),
            PublishTopicMessageRequest("topic-arn", "payload", "subject"),
            TopicPublishResponse("topic-arn", "message-id"),
            DlqSetupRequest("orders", "orders-dlq", maxReceiveCount = 3),
            DlqSetupResponse("queue-url", "dlq-url", "dlq-arn", 3),
        )

        models.forEach { model ->
            model.shouldBeInstanceOf<Serializable>()
            roundTrip(model) shouldBeEqualTo model
        }

        listOf(
            QueueResponse::class.java,
            SendQueueMessageRequest::class.java,
            QueueSendResponse::class.java,
            QueueMessageResponse::class.java,
            FanoutSetupRequest::class.java,
            FanoutSetupResponse::class.java,
            PublishTopicMessageRequest::class.java,
            TopicPublishResponse::class.java,
            DlqSetupRequest::class.java,
            DlqSetupResponse::class.java,
        ).forEach { type ->
            type.getDeclaredField("serialVersionUID").apply { isAccessible = true }.getLong(null) shouldBeEqualTo 1L
        }
    }

    @Test
    fun `request models use bluetape validation helpers for simple boundaries`() {
        assertInvalid("message") {
            SendQueueMessageRequest(" ")
        }
        assertInvalid("topicName") {
            FanoutSetupRequest(" ", "orders")
        }
        assertInvalid("queueName") {
            FanoutSetupRequest("topic", " ")
        }
        assertInvalid("topicArn") {
            PublishTopicMessageRequest(" ", "payload")
        }
        assertInvalid("message") {
            PublishTopicMessageRequest("topic-arn", " ")
        }
        assertInvalid("subject") {
            PublishTopicMessageRequest("topic-arn", "payload", " ")
        }
        assertInvalid("queueName") {
            DlqSetupRequest(" ", "orders-dlq")
        }
        assertInvalid("dlqName") {
            DlqSetupRequest("orders", " ")
        }
        assertInvalid("maxReceiveCount") {
            DlqSetupRequest("orders", "orders-dlq", maxReceiveCount = 0)
        }
    }

    private fun assertInvalid(field: String, block: () -> Unit) {
        val error = assertFailsWith<IllegalArgumentException>(block = block)
        error.message.orEmpty() shouldContain field
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() as T }
    }
}
