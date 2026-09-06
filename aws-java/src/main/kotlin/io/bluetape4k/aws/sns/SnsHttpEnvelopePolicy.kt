package io.bluetape4k.aws.sns

import java.io.Serializable
import java.net.URI
import java.util.concurrent.CancellationException

private const val MIN_SNS_TOPIC_ARN_PARTS: Int = 6
private const val SNS_ARN_PREFIX_INDEX: Int = 0
private const val SNS_ARN_PARTITION_INDEX: Int = 1
private const val SNS_ARN_SERVICE_INDEX: Int = 2
private const val SNS_ARN_REGION_INDEX: Int = 3
private const val SNS_SIGNING_CERT_SERVICE_INDEX: Int = 0
private const val SNS_SIGNING_CERT_REGION_INDEX: Int = 1
private const val SNS_AMAZONAWS_INDEX: Int = 2

/** Amazon SNS HTTP(S) endpoint가 전달하는 envelope 타입입니다. */
enum class SnsHttpEnvelopeType(val value: String) {
    /** 알림 전달 메시지입니다. */
    NOTIFICATION("Notification"),

    /** 구독 확인 메시지입니다. */
    SUBSCRIPTION_CONFIRMATION("SubscriptionConfirmation"),

    /** 구독 해제 확인 메시지입니다. */
    UNSUBSCRIBE_CONFIRMATION("UnsubscribeConfirmation"),
    ;

    companion object {
        /** SNS wire 값을 공통 envelope 타입으로 변환합니다. */
        fun from(value: String): SnsHttpEnvelopeType =
            entries.firstOrNull { it.value == value }
                ?: fail(
                    SnsHttpEnvelopeRejectionReason.UNSUPPORTED_TYPE,
                    "Unsupported SNS HTTP message type.",
                )
    }
}

/** SNS HTTP envelope을 거부한 저카디널리티 사유입니다. */
enum class SnsHttpEnvelopeRejectionReason {
    /** 본문이 비어 있습니다. */
    BODY_REQUIRED,

    /** 본문이 허용된 byte 상한을 넘었습니다. */
    BODY_TOO_LARGE,

    /** JSON을 object field map으로 decode할 수 없습니다. */
    INVALID_JSON,

    /** 필수 field가 없거나 비어 있습니다. */
    REQUIRED_FIELD,

    /** field 값이 문자열이 아닙니다. */
    INVALID_FIELD_TYPE,

    /** 지원하지 않는 SNS message type입니다. */
    UNSUPPORTED_TYPE,

    /** HTTP header와 JSON type이 일치하지 않습니다. */
    HEADER_TYPE_MISMATCH,

    /** topic ARN 구조가 유효하지 않습니다. */
    INVALID_TOPIC_ARN,

    /** 일반 URI field 문법이 유효하지 않습니다. */
    INVALID_URI,

    /** 서명 인증서 URI allowlist를 충족하지 않습니다. */
    INVALID_SIGNING_CERT_URI,

    /** message type별 필수·금지 field 계약을 충족하지 않습니다. */
    TYPE_CONTRACT,
}

/**
 * SNS HTTP envelope 구조 검증 실패입니다.
 *
 * [reason]과 message는 raw payload, signature, token, 전체 URI를 포함하지 않습니다.
 */
class SnsHttpEnvelopeValidationException(
    val reason: SnsHttpEnvelopeRejectionReason,
    message: String,
): IllegalArgumentException(message) {
    companion object {
        private const val serialVersionUID: Long = 7680306148935210142L
    }
}

/**
 * framework와 JSON decoder에 독립적인 SNS HTTP envelope입니다.
 *
 * 이 값은 구조만 검증했으며 인증되지 않았습니다. 호출자는 사용 전에 SNS 서명,
 * 인증서 체인, 예상 topic ARN과 재생 정책을 검증해야 합니다. [raw]는 decoder가 반환한
 * top-level map의 방어적 복사본입니다.
 */
data class SnsHttpEnvelope(
    val type: SnsHttpEnvelopeType,
    val messageId: String,
    val topicArn: String,
    val message: String,
    val timestamp: String,
    val signatureVersion: String,
    val signature: String,
    val signingCertUrl: URI,
    val subject: String? = null,
    val token: String? = null,
    val subscribeUrl: URI? = null,
    val unsubscribeUrl: URI? = null,
    val raw: Map<String, Any?> = emptyMap(),
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -7478829774472105078L
    }
}

/**
 * Ktor와 Spring adapter가 공유하는 SNS HTTP decoded-envelope 검증 정책입니다.
 *
 * JSON decode 자체는 adapter가 제공한 [decoder]가 소유합니다. 이 정책은 byte 상한을 먼저
 * 검사하고 decoder를 한 번 호출한 뒤 field, type/header, ARN과 URI를 검증합니다.
 */
object SnsHttpEnvelopePolicy {

    /** 기본 SNS HTTP JSON 본문 상한인 256 KiB입니다. */
    const val MAX_MESSAGE_BYTES: Int = 256 * 1024

    /** SNS HTTP message type header 이름입니다. */
    const val MESSAGE_TYPE_HEADER: String = "x-amz-sns-message-type"

    /**
     * SNS HTTP JSON 본문을 decode하고 공통 구조 정책을 적용합니다.
     *
     * @param decoder framework adapter가 제공하는 JSON object decoder입니다.
     * @throws SnsHttpEnvelopeValidationException 신뢰할 수 없는 입력이 공통 정책을 위반한 경우
     */
    fun parse(
        json: String,
        messageTypeHeader: String? = null,
        maxMessageBytes: Int = MAX_MESSAGE_BYTES,
        decoder: (String) -> Map<String, Any?>,
    ): SnsHttpEnvelope {
        require(maxMessageBytes > 0) { "maxMessageBytes must be positive." }
        if (json.isBlank()) {
            fail(SnsHttpEnvelopeRejectionReason.BODY_REQUIRED, "SNS HTTP message body must not be blank.")
        }
        if (json.toByteArray(Charsets.UTF_8).size > maxMessageBytes) {
            fail(SnsHttpEnvelopeRejectionReason.BODY_TOO_LARGE, "SNS HTTP message exceeds maxMessageBytes.")
        }

        val values = try {
            decoder(json)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SnsHttpEnvelopeValidationException) {
            throw error
        } catch (_: Exception) {
            fail(SnsHttpEnvelopeRejectionReason.INVALID_JSON, "SNS HTTP message JSON is invalid.")
        }
        return validate(values, messageTypeHeader)
    }

    /** 이미 decode한 JSON object field에 공통 구조 정책을 적용합니다. */
    fun validate(
        values: Map<String, Any?>,
        messageTypeHeader: String? = null,
    ): SnsHttpEnvelope {
        val type = SnsHttpEnvelopeType.from(values.requireString("Type"))
        messageTypeHeader
            ?.takeIf { it.isNotBlank() }
            ?.let { header ->
                val headerType = SnsHttpEnvelopeType.from(header.trim())
                if (headerType != type) {
                    fail(
                        SnsHttpEnvelopeRejectionReason.HEADER_TYPE_MISMATCH,
                        "$MESSAGE_TYPE_HEADER '${headerType.value}' does not match JSON Type '${type.value}'.",
                    )
                }
            }

        val topicArn = values.requireString("TopicArn")
        val topic = parseTopicArn(topicArn)
        val envelope = SnsHttpEnvelope(
            type = type,
            messageId = values.requireString("MessageId"),
            topicArn = topicArn,
            message = values.requireString("Message"),
            timestamp = values.requireString("Timestamp"),
            signatureVersion = values.requireString("SignatureVersion"),
            signature = values.requireString("Signature"),
            signingCertUrl = values.requireSigningCertUri(topic),
            subject = values.optionalString("Subject"),
            token = values.optionalString("Token"),
            subscribeUrl = values.optionalUri("SubscribeURL"),
            unsubscribeUrl = values.optionalUri("UnsubscribeURL"),
            raw = values.toMap(),
        )
        return envelope.validateTypeContract()
    }
}

private fun SnsHttpEnvelope.validateTypeContract(): SnsHttpEnvelope {
    when (type) {
        SnsHttpEnvelopeType.NOTIFICATION -> {
            if (token != null) {
                fail(SnsHttpEnvelopeRejectionReason.TYPE_CONTRACT, "Notification message must not include Token.")
            }
            if (subscribeUrl != null) {
                fail(
                    SnsHttpEnvelopeRejectionReason.TYPE_CONTRACT,
                    "Notification message must not include SubscribeURL.",
                )
            }
        }

        SnsHttpEnvelopeType.SUBSCRIPTION_CONFIRMATION,
        SnsHttpEnvelopeType.UNSUBSCRIBE_CONFIRMATION -> {
            if (token.isNullOrBlank()) {
                fail(
                    SnsHttpEnvelopeRejectionReason.TYPE_CONTRACT,
                    "${type.value} message requires Token.",
                )
            }
            if (subscribeUrl == null) {
                fail(
                    SnsHttpEnvelopeRejectionReason.TYPE_CONTRACT,
                    "${type.value} message requires SubscribeURL.",
                )
            }
        }
    }
    return this
}

private fun Map<String, Any?>.requireString(key: String): String =
    optionalString(key)
        ?: fail(
            SnsHttpEnvelopeRejectionReason.REQUIRED_FIELD,
            "SNS HTTP message requires string field $key.",
        )

private fun Map<String, Any?>.optionalString(key: String): String? {
    val value = this[key] ?: return null
    if (value !is String) {
        fail(
            SnsHttpEnvelopeRejectionReason.INVALID_FIELD_TYPE,
            "SNS HTTP message field $key must be a string.",
        )
    }
    return value.takeIf { it.isNotBlank() }
}

private fun Map<String, Any?>.optionalUri(key: String): URI? =
    optionalString(key)?.toUri()

private fun String.toUri(): URI =
    try {
        URI.create(this)
    } catch (_: IllegalArgumentException) {
        fail(SnsHttpEnvelopeRejectionReason.INVALID_URI, "SNS HTTP message URI field is invalid.")
    }

private data class SnsTopicArn(
    val partition: String,
    val region: String,
)

private fun parseTopicArn(topicArn: String): SnsTopicArn {
    val parts = topicArn.split(':')
    val hasArnShape = parts.size >= MIN_SNS_TOPIC_ARN_PARTS &&
        parts[SNS_ARN_PREFIX_INDEX] == "arn" &&
        parts[SNS_ARN_SERVICE_INDEX] == "sns"
    if (!hasArnShape || parts[SNS_ARN_PARTITION_INDEX].isBlank() || parts[SNS_ARN_REGION_INDEX].isBlank()) {
        fail(SnsHttpEnvelopeRejectionReason.INVALID_TOPIC_ARN, "topicArn must be an SNS ARN.")
    }
    return SnsTopicArn(
        partition = parts[SNS_ARN_PARTITION_INDEX],
        region = parts[SNS_ARN_REGION_INDEX],
    )
}

private fun Map<String, Any?>.requireSigningCertUri(topic: SnsTopicArn): URI {
    val value = requireString("SigningCertURL")
    val uri = try {
        URI.create(value)
    } catch (_: IllegalArgumentException) {
        failInvalidSigningCertUri()
    }
    val host = uri.host?.lowercase() ?: failInvalidSigningCertUri()
    val labels = host.split('.')

    val allowedAuthority = uri.scheme.equals("https", ignoreCase = true) &&
        uri.rawUserInfo == null &&
        uri.port == -1
    val allowedLocation = uri.rawQuery == null && uri.rawFragment == null && uri.path.endsWith(".pem")
    val allowedHost = hostMatchesPartition(labels, topic.partition) &&
        labels[SNS_SIGNING_CERT_REGION_INDEX] == topic.region
    if (!allowedAuthority || !allowedLocation || !allowedHost) {
        failInvalidSigningCertUri()
    }
    return uri
}

private fun hostMatchesPartition(labels: List<String>, partition: String): Boolean {
    val service = labels.getOrNull(SNS_SIGNING_CERT_SERVICE_INDEX)
    val serviceMatches = service == "sns" || service == "sns-fips"
    if (!serviceMatches) return false
    return when (partition) {
        "aws", "aws-us-gov" ->
            labels.drop(SNS_AMAZONAWS_INDEX) == listOf("amazonaws", "com")
        "aws-cn" ->
            labels.drop(SNS_AMAZONAWS_INDEX) == listOf("amazonaws", "com", "cn")
        else -> false
    }
}

private fun failInvalidSigningCertUri(): Nothing =
    fail(
        SnsHttpEnvelopeRejectionReason.INVALID_SIGNING_CERT_URI,
        "SNS HTTP message SigningCertURL is not allowed.",
    )

private fun fail(reason: SnsHttpEnvelopeRejectionReason, message: String): Nothing =
    throw SnsHttpEnvelopeValidationException(reason, message)
