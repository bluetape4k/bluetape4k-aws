package io.bluetape4k.aws.spring.sns

import org.springframework.boot.json.JsonParserFactory
import java.net.URI

/**
 * Amazon SNS HTTP(S) 엔드포인트 JSON 메시지 파서입니다.
 *
 * 파서는 공식 SNS 필드를 매핑하고 선택적으로 `x-amz-sns-message-type` 헤더와 JSON `Type`을
 * 대조합니다. SNS 서명은 검증하지 않으므로 호출자는 파싱된 메시지를 신뢰하기 전에
 * 인증서 체인, 서명, 예상 topic ARN을 검증해야 합니다.
 */
object SnsHttpMessageParser {

    const val MESSAGE_TYPE_HEADER: String = "x-amz-sns-message-type"

    fun parse(json: String, messageTypeHeader: String? = null): SnsHttpMessage {
        require(json.isNotBlank()) { "json must not be blank." }

        val values = JsonParserFactory.getJsonParser().parseMap(json)
        val type = SnsHttpMessageType.from(values.requireString("Type"))
        messageTypeHeader
            ?.takeIf { it.isNotBlank() }
            ?.let { header ->
                val headerType = SnsHttpMessageType.from(header.trim())
                require(headerType == type) {
                    "$MESSAGE_TYPE_HEADER '$header' does not match JSON Type '${type.value}'."
                }
            }

        val message = SnsHttpMessage(
            type = type,
            messageId = values.requireString("MessageId"),
            topicArn = values.requireString("TopicArn"),
            message = values.requireString("Message"),
            timestamp = values.requireString("Timestamp"),
            signatureVersion = values.requireString("SignatureVersion"),
            signature = values.requireString("Signature"),
            signingCertUrl = values.requireSigningCertUri(),
            subject = values.optionalString("Subject"),
            token = values.optionalString("Token"),
            subscribeUrl = values.optionalUri("SubscribeURL"),
            unsubscribeUrl = values.optionalUri("UnsubscribeURL"),
            raw = values.mapValues { it.value },
        )

        return message.validateByType()
    }

    private fun SnsHttpMessage.validateByType(): SnsHttpMessage {
        when (type) {
            SnsHttpMessageType.NOTIFICATION -> {
                require(token == null) { "Notification message must not include Token." }
                require(subscribeUrl == null) { "Notification message must not include SubscribeURL." }
            }

            SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION,
            SnsHttpMessageType.UNSUBSCRIBE_CONFIRMATION -> {
                require(!token.isNullOrBlank()) { "${type.value} message requires Token." }
                require(subscribeUrl != null) { "${type.value} message requires SubscribeURL." }
            }
        }
        return this
    }

    private fun Map<String, Any>.requireString(key: String): String =
        optionalString(key)
            ?: throw IllegalArgumentException("SNS HTTP message requires $key.")

    private fun Map<String, Any>.optionalString(key: String): String? =
        this[key]
            ?.toString()
            ?.takeIf { it.isNotBlank() }

    private fun Map<String, Any>.requireUri(key: String): URI =
        requireString(key).toUri(key)

    private fun Map<String, Any>.requireSigningCertUri(): URI =
        requireUri("SigningCertURL").also { uri ->
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "SNS HTTP message SigningCertURL must use https."
            }
            val host = requireNotNull(uri.host) {
                "SNS HTTP message SigningCertURL must include a host."
            }.lowercase()
            require((host.startsWith("sns.") || host.startsWith("sns-fips.")) &&
                (host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn"))) {
                "SNS HTTP message SigningCertURL must use an Amazon SNS host."
            }
        }

    private fun Map<String, Any>.optionalUri(key: String): URI? =
        optionalString(key)?.toUri(key)

    private fun String.toUri(key: String): URI =
        runCatching { URI.create(this) }
            .getOrElse { throw IllegalArgumentException("SNS HTTP message $key must be a valid URI.", it) }
}
