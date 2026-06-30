package io.bluetape4k.aws.ktor.sns

import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeType
import java.net.URI

private const val DEFAULT_MAX_SNS_HTTP_MESSAGE_BYTES: Int = 256 * 1024

/**
 * Parser for Amazon SNS HTTP(S) endpoint JSON messages.
 *
 * ## Contract
 *
 * The parser maps official SNS fields and optionally checks the
 * `x-amz-sns-message-type` header against the JSON `Type`. It validates JSON
 * structure and signing certificate URL shape, but it does not verify SNS
 * signatures. Callers must validate the certificate chain, signature, expected
 * topic ARN, and replay policy before trusting the parsed message.
 */
class SnsHttpMessageParser(
    private val objectMapper: ObjectMapper = defaultObjectMapper(),
    private val maxMessageBytes: Int = DEFAULT_MAX_SNS_HTTP_MESSAGE_BYTES,
) {

    init {
        require(maxMessageBytes > 0) { "maxMessageBytes must be positive." }
    }

    /**
     * Parses an SNS HTTP JSON body into an untrusted [SnsHttpMessage].
     */
    fun parse(json: String, messageTypeHeader: String? = null): SnsHttpMessage {
        require(json.isNotBlank()) { "json must not be blank." }
        require(json.toByteArray(Charsets.UTF_8).size <= maxMessageBytes) {
            "SNS HTTP message exceeds maxMessageBytes."
        }

        val root = objectMapper.readTree(json)
        require(root.isObject()) { "SNS HTTP message JSON must be an object." }

        val type = SnsHttpMessageType.from(root.requireString("Type"))
        messageTypeHeader
            ?.takeIf { it.isNotBlank() }
            ?.let { header ->
                val headerType = SnsHttpMessageType.from(header.trim())
                require(headerType == type) {
                    "$MESSAGE_TYPE_HEADER '$header' does not match JSON Type '${type.value}'."
                }
            }

        val topicArn = root.requireString("TopicArn")
        val signingCertUrl = root.requireSigningCertUri(topicArn)
        val message = SnsHttpMessage(
            type = type,
            messageId = root.requireString("MessageId"),
            topicArn = topicArn,
            message = root.requireString("Message"),
            timestamp = root.requireString("Timestamp"),
            signatureVersion = root.requireString("SignatureVersion"),
            signature = root.requireString("Signature"),
            signingCertUrl = signingCertUrl,
            subject = root.optionalString("Subject"),
            token = root.optionalString("Token"),
            subscribeUrl = root.optionalUri("SubscribeURL"),
            unsubscribeUrl = root.optionalUri("UnsubscribeURL"),
            raw = root.properties().associate { it.key to it.value.takeIf { node -> node.isStringNode() }?.stringValue() },
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

    private fun JsonNode.requireString(key: String): String =
        optionalString(key)
            ?: throw IllegalArgumentException("SNS HTTP message requires string field $key.")

    private fun JsonNode.optionalString(key: String): String? {
        val value = get(key) ?: return null
        require(value.isStringNode()) { "SNS HTTP message field $key must be a string." }
        return value.stringValue().takeIf { it.isNotBlank() }
    }

    private fun JsonNode.requireUri(key: String): URI =
        requireString(key).toUri(key)

    private fun JsonNode.optionalUri(key: String): URI? =
        optionalString(key)?.toUri(key)

    private fun JsonNode.requireSigningCertUri(topicArn: String): URI =
        requireUri("SigningCertURL").also { uri ->
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "SNS HTTP message SigningCertURL must use https."
            }
            require(uri.rawUserInfo == null) { "SNS HTTP message SigningCertURL must not include userinfo." }
            require(uri.rawQuery == null) { "SNS HTTP message SigningCertURL must not include query." }
            require(uri.rawFragment == null) { "SNS HTTP message SigningCertURL must not include fragment." }
            require(uri.port == -1) { "SNS HTTP message SigningCertURL must not include a custom port." }
            require(uri.path.endsWith(".pem")) { "SNS HTTP message SigningCertURL path must end with .pem." }

            val topic = SnsTopicArn.parse(topicArn)
            val host = requireNotNull(uri.host) {
                "SNS HTTP message SigningCertURL must include a host."
            }.lowercase()
            val hostRegion = signingCertRegion(host)
            require(hostRegion == topic.region) {
                "SNS HTTP message SigningCertURL region must match TopicArn region."
            }
            require(hostPartitionMatchesTopic(host, topic.partition)) {
                "SNS HTTP message SigningCertURL partition must match TopicArn partition."
            }
        }

    private fun String.toUri(key: String): URI =
        runCatching { URI.create(this) }
            .getOrElse { throw IllegalArgumentException("SNS HTTP message $key must be a valid URI.", it) }

    companion object {
        /** SNS HTTP message type header. */
        const val MESSAGE_TYPE_HEADER: String = "x-amz-sns-message-type"

        /** Builds a default parser with strict duplicate-field detection. */
        fun default(): SnsHttpMessageParser = SnsHttpMessageParser()

        private fun defaultObjectMapper(): ObjectMapper =
            ObjectMapper(
                JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build()
            )
    }
}

private class SnsTopicArn(
    val partition: String,
    val region: String,
) {
    companion object {
        fun parse(topicArn: String): SnsTopicArn {
            val parts = topicArn.split(':')
            require(parts.size >= 6 && parts[0] == "arn" && parts[2] == "sns") {
                "topicArn must be an SNS ARN."
            }
            require(parts[1].isNotBlank()) { "topicArn partition must not be blank." }
            require(parts[3].isNotBlank()) { "topicArn region must not be blank." }
            return SnsTopicArn(partition = parts[1], region = parts[3])
        }
    }
}

private fun signingCertRegion(host: String): String {
    val labels = host.split('.')
    require(labels.size >= 4) { "SNS HTTP message SigningCertURL must use an Amazon SNS host." }
    return when (labels[0]) {
        "sns", "sns-fips" -> labels[1]
        else -> throw IllegalArgumentException("SNS HTTP message SigningCertURL must use an Amazon SNS host.")
    }
}

private fun hostPartitionMatchesTopic(host: String, partition: String): Boolean =
    when (partition) {
        "aws-cn" -> host.endsWith(".amazonaws.com.cn")
        "aws", "aws-us-gov" -> host.endsWith(".amazonaws.com")
        else -> false
    }

private fun JsonNode.isStringNode(): Boolean =
    nodeType == JsonNodeType.STRING
