package io.bluetape4k.aws.spring.sns

import org.springframework.boot.json.JsonParserFactory
import java.net.URI

private const val MIN_SNS_TOPIC_ARN_PARTS: Int = 6
private const val MIN_SNS_SIGNING_CERT_HOST_LABELS: Int = 4
private const val SNS_ARN_PREFIX_INDEX: Int = 0
private const val SNS_ARN_PARTITION_INDEX: Int = 1
private const val SNS_ARN_SERVICE_INDEX: Int = 2
private const val SNS_ARN_REGION_INDEX: Int = 3
private const val SNS_SIGNING_CERT_SERVICE_LABEL_INDEX: Int = 0
private const val SNS_SIGNING_CERT_REGION_LABEL_INDEX: Int = 1

/**
 * Amazon SNS HTTP(S) 엔드포인트 JSON 메시지 파서입니다.
 *
 * 파서는 공식 SNS 필드를 매핑하고 선택적으로 `x-amz-sns-message-type` 헤더와 JSON `Type`을
 * 대조합니다. 신뢰할 수 없는 입력의 크기·타입·인증서 URL 형식을 검증하지만 SNS 서명은
 * 검증하지 않으므로 호출자는 파싱된 메시지를 신뢰하기 전에 인증서 체인, 서명, 예상 topic ARN을
 * 검증해야 합니다.
 */
object SnsHttpMessageParser {

    const val MESSAGE_TYPE_HEADER: String = "x-amz-sns-message-type"

    fun parse(json: String, messageTypeHeader: String? = null): SnsHttpMessage {
        require(json.isNotBlank()) { "json must not be blank." }
        require(json.toByteArray(Charsets.UTF_8).size <= SnsHttpMessageLimits.MAX_BYTES) {
            "SNS HTTP message exceeds maxMessageBytes."
        }

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

        val topicArn = values.requireString("TopicArn")
        val message = SnsHttpMessage(
            type = type,
            messageId = values.requireString("MessageId"),
            topicArn = topicArn,
            message = values.requireString("Message"),
            timestamp = values.requireString("Timestamp"),
            signatureVersion = values.requireString("SignatureVersion"),
            signature = values.requireString("Signature"),
            signingCertUrl = values.requireSigningCertUri(topicArn),
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
            ?: throw IllegalArgumentException("SNS HTTP message requires string field $key.")

    private fun Map<String, Any>.optionalString(key: String): String? =
        (this[key] ?: return null).let { value ->
            require(value is String) { "SNS HTTP message field $key must be a string." }
            value.takeIf { it.isNotBlank() }
        }

    private fun Map<String, Any>.requireUri(key: String): URI =
        requireString(key).toUri(key)

    private fun Map<String, Any>.requireSigningCertUri(topicArn: String): URI =
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

    private fun Map<String, Any>.optionalUri(key: String): URI? =
        optionalString(key)?.toUri(key)

    private fun String.toUri(key: String): URI =
        runCatching { URI.create(this) }
            .getOrElse { throw IllegalArgumentException("SNS HTTP message $key must be a valid URI.", it) }
}

private class SnsTopicArn(
    val partition: String,
    val region: String,
) {
    companion object {
        fun parse(topicArn: String): SnsTopicArn {
            val parts = topicArn.split(':')
            require(
                parts.size >= MIN_SNS_TOPIC_ARN_PARTS &&
                    parts[SNS_ARN_PREFIX_INDEX] == "arn" &&
                    parts[SNS_ARN_SERVICE_INDEX] == "sns",
            ) {
                "topicArn must be an SNS ARN."
            }
            require(parts[SNS_ARN_PARTITION_INDEX].isNotBlank()) { "topicArn partition must not be blank." }
            require(parts[SNS_ARN_REGION_INDEX].isNotBlank()) { "topicArn region must not be blank." }
            return SnsTopicArn(
                partition = parts[SNS_ARN_PARTITION_INDEX],
                region = parts[SNS_ARN_REGION_INDEX],
            )
        }
    }
}

private fun signingCertRegion(host: String): String {
    val labels = host.split('.')
    require(labels.size >= MIN_SNS_SIGNING_CERT_HOST_LABELS) {
        "SNS HTTP message SigningCertURL must use an Amazon SNS host."
    }
    return when (labels[SNS_SIGNING_CERT_SERVICE_LABEL_INDEX]) {
        "sns", "sns-fips" -> labels[SNS_SIGNING_CERT_REGION_LABEL_INDEX]
        else -> throw IllegalArgumentException("SNS HTTP message SigningCertURL must use an Amazon SNS host.")
    }
}

private fun hostPartitionMatchesTopic(host: String, partition: String): Boolean =
    when (partition) {
        "aws-cn" -> host.endsWith(".amazonaws.com.cn")
        "aws", "aws-us-gov" -> host.endsWith(".amazonaws.com")
        else -> false
    }
