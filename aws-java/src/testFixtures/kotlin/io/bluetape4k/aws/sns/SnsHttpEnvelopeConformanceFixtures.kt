package io.bluetape4k.aws.sns

/** SNS HTTP adapter가 함께 소비하는 유효 envelope 사례입니다. */
data class SnsHttpEnvelopeValidCase(
    val name: String,
    val json: String,
    val messageTypeHeader: String?,
    val expectedType: SnsHttpEnvelopeType,
    val expectedMessageId: String,
)

/** SNS HTTP adapter가 함께 소비하는 거부 envelope 사례입니다. */
data class SnsHttpEnvelopeInvalidCase(
    val name: String,
    val json: String,
    val messageTypeHeader: String? = null,
    val expectedReason: SnsHttpEnvelopeRejectionReason,
    val expectedMessage: String,
)

/**
 * Ktor와 Spring SNS HTTP adapter의 구조 검증 결과를 고정하는 공통 corpus입니다.
 *
 * 이 fixture는 JSON decoder를 선택하지 않으며 실제 signature, 인증서 체인, IAM 정책을 검증하지 않습니다.
 */
object SnsHttpEnvelopeConformanceFixtures {

    private val hostileSigningCertUrls: List<String> = listOf(
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
    )

    val validCases: List<SnsHttpEnvelopeValidCase> = listOf(
        SnsHttpEnvelopeValidCase(
            name = "notification",
            json = notificationJson(),
            messageTypeHeader = "Notification",
            expectedType = SnsHttpEnvelopeType.NOTIFICATION,
            expectedMessageId = "message-1",
        ),
        SnsHttpEnvelopeValidCase(
            name = "subscription-confirmation",
            json = confirmationJson("SubscriptionConfirmation"),
            messageTypeHeader = "SubscriptionConfirmation",
            expectedType = SnsHttpEnvelopeType.SUBSCRIPTION_CONFIRMATION,
            expectedMessageId = "message-1",
        ),
        SnsHttpEnvelopeValidCase(
            name = "unsubscribe-confirmation",
            json = confirmationJson("UnsubscribeConfirmation"),
            messageTypeHeader = "UnsubscribeConfirmation",
            expectedType = SnsHttpEnvelopeType.UNSUBSCRIBE_CONFIRMATION,
            expectedMessageId = "message-1",
        ),
        SnsHttpEnvelopeValidCase(
            name = "govcloud-fips-certificate",
            json = notificationJson(
                "TopicArn" to quoted("arn:aws-us-gov:sns:us-gov-west-1:123456789012:orders"),
                "SigningCertURL" to quoted(
                    "https://sns-fips.us-gov-west-1.amazonaws.com/SimpleNotificationService.pem",
                ),
            ),
            messageTypeHeader = null,
            expectedType = SnsHttpEnvelopeType.NOTIFICATION,
            expectedMessageId = "message-1",
        ),
        SnsHttpEnvelopeValidCase(
            name = "china-certificate",
            json = notificationJson(
                "TopicArn" to quoted("arn:aws-cn:sns:cn-north-1:123456789012:orders"),
                "SigningCertURL" to quoted(
                    "https://sns.cn-north-1.amazonaws.com.cn/SimpleNotificationService.pem",
                ),
            ),
            messageTypeHeader = null,
            expectedType = SnsHttpEnvelopeType.NOTIFICATION,
            expectedMessageId = "message-1",
        ),
        SnsHttpEnvelopeValidCase(
            name = "exact-byte-limit",
            json = exactLimitJson(),
            messageTypeHeader = " Notification ",
            expectedType = SnsHttpEnvelopeType.NOTIFICATION,
            expectedMessageId = "message-1",
        ),
    )

    val invalidCases: List<SnsHttpEnvelopeInvalidCase> = buildList {
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "blank-body",
                json = "   ",
                expectedReason = SnsHttpEnvelopeRejectionReason.BODY_REQUIRED,
                expectedMessage = "SNS HTTP message body must not be blank.",
            )
        )
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "oversized-body",
                json = exactLimitJson() + " ",
                expectedReason = SnsHttpEnvelopeRejectionReason.BODY_TOO_LARGE,
                expectedMessage = "SNS HTTP message exceeds maxMessageBytes.",
            )
        )
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "malformed-json",
                json = "{\"Signature\":\"signature-secret\"",
                expectedReason = SnsHttpEnvelopeRejectionReason.INVALID_JSON,
                expectedMessage = "SNS HTTP message JSON is invalid.",
            )
        )
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "array-root",
                json = "[]",
                expectedReason = SnsHttpEnvelopeRejectionReason.INVALID_JSON,
                expectedMessage = "SNS HTTP message JSON is invalid.",
            )
        )
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "duplicate-type",
                json = notificationJson().replace(
                    "{",
                    "{\"Type\":\"SubscriptionConfirmation\",",
                ),
                expectedReason = SnsHttpEnvelopeRejectionReason.INVALID_JSON,
                expectedMessage = "SNS HTTP message JSON is invalid.",
            )
        )
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "missing-message-id",
                json = notificationJson("MessageId" to null),
                expectedReason = SnsHttpEnvelopeRejectionReason.REQUIRED_FIELD,
                expectedMessage = "SNS HTTP message requires string field MessageId.",
            )
        )
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "non-string-message-id",
                json = notificationJson("MessageId" to "1"),
                expectedReason = SnsHttpEnvelopeRejectionReason.INVALID_FIELD_TYPE,
                expectedMessage = "SNS HTTP message field MessageId must be a string.",
            )
        )
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "unsupported-type",
                json = notificationJson("Type" to quoted("Unknown")),
                expectedReason = SnsHttpEnvelopeRejectionReason.UNSUPPORTED_TYPE,
                expectedMessage = "Unsupported SNS HTTP message type.",
            )
        )
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "header-mismatch",
                json = notificationJson(),
                messageTypeHeader = "SubscriptionConfirmation",
                expectedReason = SnsHttpEnvelopeRejectionReason.HEADER_TYPE_MISMATCH,
                expectedMessage =
                    "x-amz-sns-message-type 'SubscriptionConfirmation' does not match JSON Type 'Notification'.",
            )
        )
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "invalid-topic-arn",
                json = notificationJson("TopicArn" to quoted("not-an-arn")),
                expectedReason = SnsHttpEnvelopeRejectionReason.INVALID_TOPIC_ARN,
                expectedMessage = "topicArn must be an SNS ARN.",
            )
        )
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "confirmation-without-token",
                json = confirmationJson("SubscriptionConfirmation", "Token" to null),
                expectedReason = SnsHttpEnvelopeRejectionReason.TYPE_CONTRACT,
                expectedMessage = "SubscriptionConfirmation message requires Token.",
            )
        )
        add(
            SnsHttpEnvelopeInvalidCase(
                name = "invalid-subscribe-uri",
                json = confirmationJson("SubscriptionConfirmation", "SubscribeURL" to quoted("https://[invalid")),
                expectedReason = SnsHttpEnvelopeRejectionReason.INVALID_URI,
                expectedMessage = "SNS HTTP message URI field is invalid.",
            )
        )
        hostileSigningCertUrls.forEachIndexed { index, url ->
            add(
                SnsHttpEnvelopeInvalidCase(
                    name = "hostile-signing-certificate-$index",
                    json = notificationJson("SigningCertURL" to quoted(url)),
                    expectedReason = SnsHttpEnvelopeRejectionReason.INVALID_SIGNING_CERT_URI,
                    expectedMessage = "SNS HTTP message SigningCertURL is not allowed.",
                )
            )
        }
    }

    private fun confirmationJson(
        type: String,
        vararg overrides: Pair<String, String?>,
    ): String = notificationJson(
        "Type" to quoted(type),
        "Token" to quoted("token-1"),
        "SubscribeURL" to quoted("https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription"),
        *overrides,
    )

    private fun exactLimitJson(): String {
        val emptyMessage = notificationJson("Message" to quoted(""))
        val paddingBytes = SnsHttpEnvelopePolicy.MAX_MESSAGE_BYTES - emptyMessage.toByteArray(Charsets.UTF_8).size
        check(paddingBytes >= 0)
        return notificationJson("Message" to quoted("x".repeat(paddingBytes)))
    }

    private fun notificationJson(vararg overrides: Pair<String, String?>): String {
        val fields = linkedMapOf<String, String?>(
            "Type" to quoted("Notification"),
            "MessageId" to quoted("message-1"),
            "TopicArn" to quoted("arn:aws:sns:us-east-1:123456789012:orders"),
            "Message" to quoted("payload"),
            "Timestamp" to quoted("2026-09-06T00:00:00Z"),
            "SignatureVersion" to quoted("2"),
            "Signature" to quoted("signature-secret"),
            "SigningCertURL" to quoted(
                "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem",
            ),
        )
        overrides.forEach { (key, value) -> fields[key] = value }
        return fields.entries
            .filter { it.value != null }
            .joinToString(prefix = "{", postfix = "}") { (key, value) -> "${quoted(key)}:$value" }
    }

    private fun quoted(value: String): String = "\"$value\""
}
