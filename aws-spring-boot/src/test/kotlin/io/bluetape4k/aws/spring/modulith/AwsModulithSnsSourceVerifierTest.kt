package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.aws.spring.sns.SnsHttpMessage
import io.bluetape4k.aws.spring.sns.SnsHttpMessageParser
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import java.util.concurrent.atomic.AtomicInteger

class AwsModulithSnsSourceVerifierTest {

    @Test
    fun `DIRECT rejects an SNS notification before envelope decode`() {
        val decoder = decoder(AwsModulithSourceMode.DIRECT)

        listOf("Notification", "SubscriptionConfirmation", "UnsubscribeConfirmation").forEach { type ->
            val body = notification().replace("\"Notification\"", "\"$type\"")
            kotlin.runCatching { decoder.decode(message(body)) }.exceptionOrNull()
                .shouldBeInstanceOf<AwsModulithSourceException>()
        }
    }

    @Test
    fun `DIRECT decodes only the bounded body and String attributes`() {
        val codec = RecordingCodec()
        val decoder = decoder(AwsModulithSourceMode.DIRECT, codec = codec)

        val decoded = decoder.decode(message(ENVELOPE, directAttributes()))

        codec.body shouldBeEqualTo ENVELOPE
        decoded.key shouldBeEqualTo AwsModulithEventKey("orders.created", "evt-1")
    }

    @Test
    fun `SNS checks exact topic allowlist before calling signature verifier`() {
        val calls = AtomicInteger()
        val decoder = decoder(
            mode = AwsModulithSourceMode.SNS,
            expectedArns = setOf(EXPECTED_ARN),
            verifier = AwsModulithSnsNotificationVerifier { json, arn ->
                calls.incrementAndGet()
                SnsHttpMessageParser.parse(json).also { it.topicArn shouldBeEqualTo arn }
            },
        )

        kotlin.runCatching {
            decoder.decode(message(notification(topicArn = UNEXPECTED_ARN)))
        }.exceptionOrNull().shouldBeInstanceOf<AwsModulithSourceException>()
        calls.get() shouldBeEqualTo 0
    }

    @Test
    fun `SNS requires signature verification and returns the verified inner envelope`() {
        val calls = AtomicInteger()
        val codec = RecordingCodec()
        val decoder = decoder(
            mode = AwsModulithSourceMode.SNS,
            codec = codec,
            expectedArns = setOf(EXPECTED_ARN),
            verifier = AwsModulithSnsNotificationVerifier { json, arn ->
                calls.incrementAndGet()
                SnsHttpMessageParser.parse(json, expectedTopicArn = arn)
            },
        )

        val decoded = decoder.decode(message(notification(message = ENVELOPE)))

        calls.get() shouldBeEqualTo 1
        codec.body shouldBeEqualTo ENVELOPE
        decoded.key shouldBeEqualTo AwsModulithEventKey("orders.created", "evt-1")
    }

    @Test
    fun `structurally valid unsigned SNS notification is rejected`() {
        val decoder = decoder(AwsModulithSourceMode.SNS, expectedArns = setOf(EXPECTED_ARN))

        kotlin.runCatching { decoder.decode(message(notification())) }.exceptionOrNull()
            .shouldBeInstanceOf<AwsModulithSourceException>()
    }

    @Test
    fun `SNS rejects oversize duplicate unknown and non Notification input`() {
        val decoder = decoder(AwsModulithSourceMode.SNS, expectedArns = setOf(EXPECTED_ARN))
        val hostile = listOf(
            " ".repeat(262_145),
            notification().replace("\"TopicArn\":", "\"TopicArn\":\"$EXPECTED_ARN\",\"TopicArn\":"),
            notification().replace("\"Message\":", "\"Message\":\"duplicate\",\"Message\":"),
            notification().replace("\"Message\":${quote(ENVELOPE)},", ""),
            notification().dropLast(1) + ",\"Unknown\":\"x\"}",
            notification().replace("\"Notification\"", "\"SubscriptionConfirmation\""),
        )

        hostile.forEach { json ->
            kotlin.runCatching { decoder.decode(message(json)) }.exceptionOrNull()
                .shouldBeInstanceOf<AwsModulithSourceException>()
        }
    }

    @Test
    fun `SNS preflight accepts exact byte string number and depth limits and rejects one over`() {
        val decoder = decoder(
            AwsModulithSourceMode.SNS,
            expectedArns = setOf(EXPECTED_ARN),
            verifier = trustedVerifier(),
        )
        val exactBytes = notification().let { json ->
            json + " ".repeat(MAX_SOURCE_BYTES - json.toByteArray().size)
        }
        val exactString = notification().dropLast(1) +
            ",\"Subject\":\"${"x".repeat(MAX_STRING_LENGTH)}\"}"
        val overString = notification().dropLast(1) +
            ",\"Subject\":\"${"x".repeat(MAX_STRING_LENGTH + 1)}\"}"
        val exactUtf8String = notification().dropLast(1) +
            ",\"Subject\":\"${"가".repeat(MAX_STRING_LENGTH / 3)}\"}"
        val overUtf8String = notification().dropLast(1) +
            ",\"Subject\":\"${"가".repeat(MAX_STRING_LENGTH / 3 + 1)}\"}"
        val exactNumber = notification(messageAttributes = "{\"x\":${number(MAX_NUMBER_LENGTH)}}")
        val overNumber = notification(messageAttributes = "{\"x\":${number(MAX_NUMBER_LENGTH + 1)}}")
        val exactDepth = notification(messageAttributes = nestedArrays(MAX_DEPTH - 1))
        val overDepth = notification(messageAttributes = nestedArrays(MAX_DEPTH))

        listOf(exactBytes, exactString, exactUtf8String, exactNumber, exactDepth).forEach { json ->
            decoder.decode(message(json)).key shouldBeEqualTo AwsModulithEventKey("orders.created", "evt-1")
        }
        listOf(overString, overUtf8String, overNumber, overDepth).forEach { json ->
            kotlin.runCatching { decoder.decode(message(json)) }.exceptionOrNull()
                .shouldBeInstanceOf<AwsModulithSourceException>()
        }
    }

    @Test
    fun `SNS preflight enforces the exact token count`() {
        val decoder = decoder(
            AwsModulithSourceMode.SNS,
            expectedArns = setOf(EXPECTED_ARN),
            verifier = trustedVerifier(),
        )
        val exact = notification(messageAttributes = numericArray(EXACT_ARRAY_VALUES))
        val over = notification(messageAttributes = numericArray(EXACT_ARRAY_VALUES + 1))

        decoder.decode(message(exact)).key shouldBeEqualTo AwsModulithEventKey("orders.created", "evt-1")
        kotlin.runCatching { decoder.decode(message(over)) }.exceptionOrNull()
            .shouldBeInstanceOf<AwsModulithSourceException>()
    }

    @Test
    fun `SNS preflight requires every required scalar field to be a non-blank string`() {
        val decoder = decoder(
            AwsModulithSourceMode.SNS,
            expectedArns = setOf(EXPECTED_ARN),
            verifier = trustedVerifier(),
        )

        REQUIRED_FIELDS.forEach { (name, value) ->
            listOf("null", "{}", quote(" ")).forEach { replacement ->
                val hostile = notification().replace(
                    "\"$name\":${quote(value)}",
                    "\"$name\":$replacement",
                )
                kotlin.runCatching { decoder.decode(message(hostile)) }.exceptionOrNull()
                    .shouldBeInstanceOf<AwsModulithSourceException>()
            }
        }
    }

    private fun decoder(
        mode: AwsModulithSourceMode,
        codec: AwsModulithEventCodec = RecordingCodec(),
        expectedArns: Set<String> = emptySet(),
        verifier: AwsModulithSnsNotificationVerifier = AwsModulithSnsNotificationVerifier { _, _ ->
            throw IllegalStateException("unsigned")
        },
    ) = DefaultAwsModulithInboundSourceDecoder(mode, expectedArns, codec, verifier)

    private fun message(
        body: String,
        attributes: Map<String, MessageAttributeValue> = emptyMap(),
    ): SqsReceivedMessage = SqsReceivedMessage(
        queueUrl = "http://localhost/queue/events",
        message = Message.builder()
            .messageId("message-1")
            .receiptHandle("receipt")
            .body(body)
            .messageAttributes(attributes)
            .build(),
    )

    private fun directAttributes(): Map<String, MessageAttributeValue> = mapOf(
        "bt4k-event-id" to stringAttribute("evt-1"),
        "bt4k-event-type" to stringAttribute("orders.created"),
        "bt4k-event-version" to stringAttribute("1"),
    )

    private fun stringAttribute(value: String): MessageAttributeValue = MessageAttributeValue.builder()
        .dataType("String")
        .stringValue(value)
        .build()

    private fun trustedVerifier(): AwsModulithSnsNotificationVerifier =
        AwsModulithSnsNotificationVerifier { _, _ -> SnsHttpMessageParser.parse(notification()) }

    private fun notification(
        topicArn: String = EXPECTED_ARN,
        message: String = ENVELOPE,
        messageAttributes: String = VALID_ATTRIBUTES,
    ): String = """{"Type":"Notification","MessageId":"sns-1","TopicArn":"$topicArn","Message":${quote(message)},"Timestamp":"2026-08-26T00:00:00Z","SignatureVersion":"1","Signature":"signature","SigningCertURL":"https://sns.us-east-1.amazonaws.com/cert.pem","MessageAttributes":$messageAttributes}"""

    private fun number(length: Int): String = "1" + "0".repeat(length - 1)

    private fun nestedArrays(arrayCount: Int): String = "[".repeat(arrayCount) + "0" + "]".repeat(arrayCount)

    private fun numericArray(values: Int): String = buildString(values * 2 + 2) {
        append('[')
        repeat(values) { index ->
            if (index > 0) append(',')
            append('0')
        }
        append(']')
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(character)
            }
        }
        append('"')
    }

    private class RecordingCodec : AwsModulithEventCodec {
        var body: String? = null

        override fun encode(event: Any): AwsModulithEncodedEvent = error("not used")

        override fun decode(body: String, attributes: Map<String, String>): Any {
            this.body = body
            return TestEvent("evt-1")
        }
    }

    private data class TestEvent(val id: String)

    private companion object {
        const val EXPECTED_ARN = "arn:aws:sns:us-east-1:123456789012:events"
        const val UNEXPECTED_ARN = "arn:aws:sns:us-east-1:123456789012:other"
        const val ENVELOPE = "{\"specVersion\":1}"
        const val MAX_SOURCE_BYTES = 262_144
        const val MAX_STRING_LENGTH = 196_608
        const val MAX_NUMBER_LENGTH = 1_000
        const val MAX_DEPTH = 32
        const val EXACT_ARRAY_VALUES = 99_979
        val REQUIRED_FIELDS = mapOf(
            "Type" to "Notification",
            "MessageId" to "sns-1",
            "TopicArn" to EXPECTED_ARN,
            "Message" to ENVELOPE,
            "Timestamp" to "2026-08-26T00:00:00Z",
            "SignatureVersion" to "1",
            "Signature" to "signature",
            "SigningCertURL" to "https://sns.us-east-1.amazonaws.com/cert.pem",
        )
        const val VALID_ATTRIBUTES =
            "{\"bt4k-event-id\":{\"Type\":\"String\",\"Value\":\"evt-1\"}," +
                "\"bt4k-event-type\":{\"Type\":\"String\",\"Value\":\"orders.created\"}," +
                "\"bt4k-event-version\":{\"Type\":\"String\",\"Value\":\"1\"}}"
    }
}

private fun SnsHttpMessageParser.parse(json: String, expectedTopicArn: String): SnsHttpMessage =
    parse(json).also { it.topicArn shouldBeEqualTo expectedTopicArn }
