package io.bluetape4k.aws.spring.sns

import org.springframework.boot.json.JsonParserFactory
import java.net.URI

/**
 * Parser for Amazon SNS HTTP(S) endpoint JSON messages.
 *
 * The parser maps official SNS fields and optionally checks the
 * `x-amz-sns-message-type` header against the JSON `Type`. It does not verify
 * SNS signatures; callers must validate the certificate chain, signature, and
 * expected topic ARN before trusting the parsed message.
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
