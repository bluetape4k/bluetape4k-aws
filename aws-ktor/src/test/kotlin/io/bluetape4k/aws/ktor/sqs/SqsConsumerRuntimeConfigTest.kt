package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsConsumerRuntimeConfigTest {

    private val client = mockk<SqsAsyncClient>()

    @Test
    fun `requires exactly one source queue identity`() {
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(queueUrl = null, queueName = null)
        }

        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(queueUrl = "https://sqs.local/queue", queueName = "queue")
        }
    }

    @Test
    fun `validates concurrency receive and shutdown ranges`() {
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(coroutines = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(maxMessages = 11)
        }
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(waitTimeSeconds = 21)
        }
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(shutdownTimeout = Duration.ZERO)
        }
    }

    @Test
    fun `manual dead letter queue and failure visibility are mutually exclusive`() {
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(
                deadLetterQueueUrl = "https://sqs.local/dlq",
                failureVisibilityTimeoutSeconds = 0,
            )
        }
    }

    @Test
    fun `default converter supports string byte array and raw message`() {
        val message = Message.builder()
            .body("hello")
            .receiptHandle("receipt")
            .build()

        StringOrByteArraySqsMessageConverter.convert(message, String::class) shouldBeEqualTo "hello"
        StringOrByteArraySqsMessageConverter.convert(message, ByteArray::class).decodeToString() shouldBeEqualTo "hello"
        StringOrByteArraySqsMessageConverter.convert(message, Message::class) shouldBeEqualTo message
    }

    private fun runtimeConfig(
        queueUrl: String? = "https://sqs.local/source",
        queueName: String? = null,
        coroutines: Int = 1,
        maxMessages: Int = 10,
        waitTimeSeconds: Int = 20,
        shutdownTimeout: Duration = Duration.ofSeconds(30),
        deadLetterQueueUrl: String? = null,
        failureVisibilityTimeoutSeconds: Int? = null,
    ): SqsConsumerRuntimeConfig =
        SqsConsumerRuntimeConfig(
            sqsAsyncClient = client,
            queueUrl = queueUrl,
            queueName = queueName,
            coroutines = coroutines,
            maxMessages = maxMessages,
            waitTimeSeconds = waitTimeSeconds,
            shutdownTimeout = shutdownTimeout,
            deadLetterQueueUrl = deadLetterQueueUrl,
            failureVisibilityTimeoutSeconds = failureVisibilityTimeoutSeconds,
            messageType = String::class,
            messageHandler = {},
        )
}
