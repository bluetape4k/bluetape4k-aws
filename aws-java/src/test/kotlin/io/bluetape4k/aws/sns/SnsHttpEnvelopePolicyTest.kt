package io.bluetape4k.aws.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CancellationException

class SnsHttpEnvelopePolicyTest {

    @Test
    fun `본문 상한을 decode 전에 적용한다`() {
        var decodeCount = 0

        val blank = assertFailsWith<SnsHttpEnvelopeValidationException> {
            SnsHttpEnvelopePolicy.parse("   ") {
                decodeCount++
                notificationFields()
            }
        }
        val oversized = assertFailsWith<SnsHttpEnvelopeValidationException> {
            SnsHttpEnvelopePolicy.parse("x".repeat(SnsHttpEnvelopePolicy.MAX_MESSAGE_BYTES + 1)) {
                decodeCount++
                notificationFields()
            }
        }

        blank.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.BODY_REQUIRED
        oversized.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.BODY_TOO_LARGE
        decodeCount shouldBeEqualTo 0
    }

    @Test
    fun `정확한 byte 상한에서 decoder를 한 번 호출한다`() {
        var decodeCount = 0
        val json = "x".repeat(SnsHttpEnvelopePolicy.MAX_MESSAGE_BYTES)

        val envelope = SnsHttpEnvelopePolicy.parse(json, messageTypeHeader = " Notification ") {
            decodeCount++
            notificationFields()
        }

        envelope.type shouldBeEqualTo SnsHttpEnvelopeType.NOTIFICATION
        decodeCount shouldBeEqualTo 1
    }

    @Test
    fun `decoder 실패를 payload 없는 공통 reason으로 변환한다`() {
        val secret = "signature-secret"

        val error = assertFailsWith<SnsHttpEnvelopeValidationException> {
            SnsHttpEnvelopePolicy.parse("{\"Signature\":\"$secret\"") {
                throw IllegalStateException("decoder exposed $secret")
            }
        }

        error.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.INVALID_JSON
        error.message.orEmpty() shouldBeEqualTo "SNS HTTP message JSON is invalid."
        error.message.orEmpty().contains(secret).shouldBeFalse()
    }

    @Test
    fun `decoder cancellation을 동일 인스턴스로 전파한다`() {
        val cancellation = CancellationException("caller-cancelled")

        val actual = assertFailsWith<CancellationException> {
            SnsHttpEnvelopePolicy.parse("{}") {
                throw cancellation
            }
        }

        assertSame(cancellation, actual)
    }

    @Test
    fun `notification envelope을 정규화한다`() {
        val envelope = SnsHttpEnvelopePolicy.validate(notificationFields())

        envelope.type shouldBeEqualTo SnsHttpEnvelopeType.NOTIFICATION
        envelope.messageId shouldBeEqualTo "message-1"
        envelope.topicArn shouldBeEqualTo "arn:aws:sns:us-east-1:123456789012:orders"
        envelope.message shouldBeEqualTo "payload"
        envelope.signingCertUrl shouldBeEqualTo
            URI.create("https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem")
        envelope.token.shouldBeNull()
        envelope.raw["MessageId"] shouldBeEqualTo "message-1"
    }

    @Test
    fun `두 confirmation 타입의 token과 subscribe URL을 요구한다`() {
        listOf(
            "SubscriptionConfirmation" to SnsHttpEnvelopeType.SUBSCRIPTION_CONFIRMATION,
            "UnsubscribeConfirmation" to SnsHttpEnvelopeType.UNSUBSCRIBE_CONFIRMATION,
        ).forEach { (wireType, expectedType) ->
            val envelope = SnsHttpEnvelopePolicy.validate(
                notificationFields() + mapOf(
                    "Type" to wireType,
                    "Token" to "token-1",
                    "SubscribeURL" to "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription",
                )
            )

            envelope.type shouldBeEqualTo expectedType
            envelope.token shouldBeEqualTo "token-1"
        }

        listOf("Token", "SubscribeURL").forEach { field ->
            val error = assertFailsWith<SnsHttpEnvelopeValidationException> {
                SnsHttpEnvelopePolicy.validate(
                    notificationFields() + mapOf("Type" to "SubscriptionConfirmation") - field
                )
            }
            error.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.TYPE_CONTRACT
        }
    }

    @Test
    fun `notification은 confirmation 전용 필드를 거부한다`() {
        listOf(
            "Token" to "token-1",
            "SubscribeURL" to "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription",
        ).forEach { (field, value) ->
            val error = assertFailsWith<SnsHttpEnvelopeValidationException> {
                SnsHttpEnvelopePolicy.validate(notificationFields() + (field to value))
            }

            error.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.TYPE_CONTRACT
        }
    }

    @Test
    fun `필수 field와 type header 계약을 검증한다`() {
        val missing = assertFailsWith<SnsHttpEnvelopeValidationException> {
            SnsHttpEnvelopePolicy.validate(notificationFields() - "MessageId")
        }
        val wrongType = assertFailsWith<SnsHttpEnvelopeValidationException> {
            SnsHttpEnvelopePolicy.validate(notificationFields() + ("MessageId" to 1))
        }
        val unsupported = assertFailsWith<SnsHttpEnvelopeValidationException> {
            SnsHttpEnvelopePolicy.validate(notificationFields() + ("Type" to "Unknown"))
        }
        val mismatch = assertFailsWith<SnsHttpEnvelopeValidationException> {
            SnsHttpEnvelopePolicy.validate(notificationFields(), "SubscriptionConfirmation")
        }

        missing.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.REQUIRED_FIELD
        wrongType.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.INVALID_FIELD_TYPE
        unsupported.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.UNSUPPORTED_TYPE
        mismatch.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.HEADER_TYPE_MISMATCH
        mismatch.message.orEmpty() shouldBeEqualTo
            "x-amz-sns-message-type 'SubscriptionConfirmation' does not match JSON Type 'Notification'."
    }

    @Test
    fun `topic ARN 형식을 검증한다`() {
        listOf(
            "not-an-arn",
            "arn::sns:us-east-1:123456789012:orders",
            "arn:aws:sqs:us-east-1:123456789012:orders",
            "arn:aws:sns::123456789012:orders",
        ).forEach { topicArn ->
            val error = assertFailsWith<SnsHttpEnvelopeValidationException> {
                SnsHttpEnvelopePolicy.validate(notificationFields() + ("TopicArn" to topicArn))
            }
            error.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.INVALID_TOPIC_ARN
        }
    }

    @Test
    fun `signing certificate URI allowlist를 검증한다`() {
        listOf(
            "http://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem",
            "https://user@sns.us-east-1.amazonaws.com/SimpleNotificationService.pem",
            "https://sns.us-east-1.amazonaws.com:444/SimpleNotificationService.pem",
            "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem?x=1",
            "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem#fragment",
            "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.txt",
            "https://example.com/SimpleNotificationService.pem",
            "https://sns/SimpleNotificationService.pem",
            "https://sns.us-east-1.evil.amazonaws.com/SimpleNotificationService.pem",
            "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
            "https://sns.us-east-1.amazonaws.com.cn/SimpleNotificationService.pem",
        ).forEach { signingCertUrl ->
            val error = assertFailsWith<SnsHttpEnvelopeValidationException> {
                SnsHttpEnvelopePolicy.validate(notificationFields() + ("SigningCertURL" to signingCertUrl))
            }

            error.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.INVALID_SIGNING_CERT_URI
            error.message.orEmpty().contains(signingCertUrl).shouldBeFalse()
        }
    }

    @Test
    fun `partition별 signing certificate host를 허용한다`() {
        listOf(
            Triple(
                "arn:aws:sns:us-east-1:123456789012:orders",
                "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem",
                "us-east-1",
            ),
            Triple(
                "arn:aws-us-gov:sns:us-gov-west-1:123456789012:orders",
                "https://sns-fips.us-gov-west-1.amazonaws.com/SimpleNotificationService.pem",
                "us-gov-west-1",
            ),
            Triple(
                "arn:aws-cn:sns:cn-north-1:123456789012:orders",
                "https://sns.cn-north-1.amazonaws.com.cn/SimpleNotificationService.pem",
                "cn-north-1",
            ),
        ).forEach { (topicArn, signingCertUrl, expectedRegion) ->
            val envelope = SnsHttpEnvelopePolicy.validate(
                notificationFields() + mapOf(
                    "TopicArn" to topicArn,
                    "SigningCertURL" to signingCertUrl,
                )
            )

            envelope.topicArn.substringAfter(":sns:").substringBefore(':') shouldBeEqualTo expectedRegion
        }
    }

    private fun notificationFields(): Map<String, Any?> = linkedMapOf(
        "Type" to "Notification",
        "MessageId" to "message-1",
        "TopicArn" to "arn:aws:sns:us-east-1:123456789012:orders",
        "Message" to "payload",
        "Timestamp" to "2026-09-06T00:00:00Z",
        "SignatureVersion" to "2",
        "Signature" to "signature-secret",
        "SigningCertURL" to "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem",
    )
}
