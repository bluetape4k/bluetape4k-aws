package io.bluetape4k.aws.ktor

import aws.sdk.kotlin.services.sqs.model.MessageAttributeValue
import aws.sdk.kotlin.services.sqs.model.MessageSystemAttributeName
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.ktor.kinesis.KinesisKtorStream
import io.bluetape4k.aws.ktor.kinesis.KinesisPutRecordRequest
import io.bluetape4k.aws.ktor.kinesis.KinesisRecordFlowOptions
import io.bluetape4k.aws.ktor.kinesis.KinesisRecordFlowRequest
import io.bluetape4k.aws.ktor.kinesis.KinesisShardIteratorRequest
import io.bluetape4k.aws.ktor.kinesis.KinesisStartingPosition
import io.bluetape4k.aws.ktor.sqs.SqsConsumerFailureContext
import io.bluetape4k.aws.ktor.sqs.SqsConsumerFailurePhase
import io.bluetape4k.aws.ktor.sqs.SqsConsumerObservation
import io.bluetape4k.aws.ktor.sqs.SqsConsumerRuntimeConfig
import io.bluetape4k.aws.ktor.sqs.SqsFixedFailureVisibilityStrategy
import io.bluetape4k.aws.ktor.sqs.SqsLinearFailureVisibilityStrategy
import io.bluetape4k.aws.ktor.sqs.SqsPollBackoff
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.lang.reflect.Modifier
import java.time.Instant
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType as KotlinShardIteratorType
import aws.sdk.kotlin.services.sqs.model.Message as KotlinSqsMessage

class SerializableContractTest {

    @Test
    fun `current Kinesis value DTOs round trip through Java serialization`() {
        val values = listOf<Serializable>(
            KinesisShardIteratorRequest(
                streamName = "orders",
                shardId = "shardId-000000000000",
                type = KotlinShardIteratorType.AtSequenceNumber,
                startingSequenceNumber = "123",
            ),
            KinesisShardIteratorRequest(
                streamName = "orders",
                shardId = "shardId-000000000000",
                type = KotlinShardIteratorType.SdkUnknown("FUTURE_ITERATOR_TYPE"),
            ),
            KinesisRecordFlowRequest(
                streamName = "orders",
                shardId = "shardId-000000000000",
                position = KinesisStartingPosition.AtTimestamp(Instant.parse("2026-01-02T03:04:05Z")),
                options = KinesisRecordFlowOptions(
                    batchLimit = 25,
                    pollInterval = 150.millis(),
                    emptyBackoff = 350.millis(),
                    maxIteratorRetries = 2,
                    maxThrottleRetries = 4,
                    initialThrottleBackoff = 125.millis(),
                    maxThrottleBackoff = 4.seconds(),
                    jitterRatio = 0.25,
                ),
            ),
            KinesisKtorStream(shardCount = 2),
            KinesisRecordFlowOptions(
                batchLimit = 10,
                pollInterval = 100.millis(),
                emptyBackoff = 250.millis(),
                maxIteratorRetries = 1,
                maxThrottleRetries = 2,
                initialThrottleBackoff = 200.millis(),
                maxThrottleBackoff = 3.seconds(),
                jitterRatio = 0.1,
            ),
            KinesisStartingPosition.TrimHorizon,
            KinesisStartingPosition.Latest,
            KinesisStartingPosition.AtSequenceNumber("123"),
            KinesisStartingPosition.AfterSequenceNumber("122"),
            KinesisStartingPosition.AtTimestamp(Instant.parse("2026-01-02T03:04:05Z")),
        ).map { value -> value to roundTrip(value) }

        values.forEach { (original, restored) ->
            restored shouldBeEqualTo original
        }
    }

    @Test
    fun `Kinesis request payload survives serialization and defensive copying`() {
        val input = "payload".encodeToByteArray()
        val original = KinesisPutRecordRequest(
            streamName = "orders",
            partitionKey = "customer-1",
            data = input,
        )
        input[0] = '!'.code.toByte()

        val restored = roundTrip(original)

        original.data.decodeToString() shouldBeEqualTo "payload"
        restored.streamName shouldBeEqualTo original.streamName
        restored.partitionKey shouldBeEqualTo original.partitionKey
        restored.data.decodeToString() shouldBeEqualTo "payload"

        val exposed = restored.data
        exposed[0] = '!'.code.toByte()
        restored.data.decodeToString() shouldBeEqualTo "payload"
    }

    @Test
    fun `selected SQS value DTOs round trip through Java serialization`() {
        val pollBackoff = SqsPollBackoff(
            initialDelay = 125.millis(),
            maxDelay = 3.seconds(),
            multiplier = 1.5,
        )
        val visibilityStrategy = SqsFixedFailureVisibilityStrategy(timeoutSeconds = 42)
        val observation = SqsConsumerObservation(
            operation = "receive",
            outcome = "success",
            queueUrl = "https://sqs.local/orders",
            messageId = "message-1",
            duration = 17.millis(),
            tags = mapOf("message_count" to "1"),
        )

        roundTrip(pollBackoff) shouldBeEqualTo pollBackoff
        roundTrip(visibilityStrategy) shouldBeEqualTo visibilityStrategy
        roundTrip(observation) shouldBeEqualTo observation
    }

    @Test
    fun `failure context round trips through Java serialization`() {
        val original = SqsConsumerFailureContext(
            queueUrl = "https://sqs.local/orders",
            message = KotlinSqsMessage {
                messageId = "message-1"
                receiptHandle = "receipt-1"
                body = "payload"
                attributes = mapOf(MessageSystemAttributeName.ApproximateReceiveCount to "3")
                messageAttributes = mapOf(
                    "trace-bin" to MessageAttributeValue {
                        dataType = "Binary"
                        binaryValue = byteArrayOf(1, 2, 3)
                    },
                )
            },
            cause = IllegalStateException("handler failed"),
            phase = SqsConsumerFailurePhase.Handler,
        )

        val restored = roundTrip(original)

        restored.queueUrl shouldBeEqualTo original.queueUrl
        restored.message shouldBeEqualTo original.message
        restored.cause::class shouldBeEqualTo original.cause::class
        restored.cause.message shouldBeEqualTo original.cause.message
        restored.phase shouldBeEqualTo original.phase
        restored.approximateReceiveCount shouldBeEqualTo original.approximateReceiveCount
        restored.message.messageAttributes?.get("trace-bin")?.binaryValue?.contentEquals(byteArrayOf(1, 2, 3))
            .shouldBeTrue()
    }

    @Test
    fun `non-value SQS types retain Serializable marker and explicit serialVersionUID`() {
        assertSerializableContract(SqsLinearFailureVisibilityStrategy::class.java)
        assertSerializableContract(SqsConsumerRuntimeConfig::class.java)
    }

    private fun assertSerializableContract(type: Class<*>) {
        Serializable::class.java.isAssignableFrom(type).shouldBeTrue()

        val serialVersionUid = type.getDeclaredField("serialVersionUID")
        Modifier.isStatic(serialVersionUid.modifiers).shouldBeTrue()
        Modifier.isFinal(serialVersionUid.modifiers).shouldBeTrue()
        serialVersionUid.type shouldBeEqualTo Long::class.javaPrimitiveType

        serialVersionUid.isAccessible = true
        serialVersionUid.getLong(null) shouldBeEqualTo 1L
    }

    private fun <T : Serializable> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { output ->
            output.writeObject(value)
        }

        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
            @Suppress("UNCHECKED_CAST")
            input.readObject() as T
        }
    }
}
