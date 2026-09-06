package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.sns.SnsHttpEnvelope
import io.bluetape4k.aws.sns.SnsHttpEnvelopePolicy
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.ObjectMapper

/**
 * Amazon SNS HTTP(S) 엔드포인트 JSON 메시지 파서입니다.
 *
 * JSON decode와 Spring 공개 모델 변환만 담당하며, decoded envelope 구조는
 * [SnsHttpEnvelopePolicy]로 검증합니다. 구조 검증은 SNS 서명 검증이 아닙니다. 호출자는
 * 메시지를 신뢰하기 전에 인증서 체인, 서명, 예상 topic ARN과 재생 정책을 검증해야 합니다.
 */
object SnsHttpMessageParser {

    const val MESSAGE_TYPE_HEADER: String = SnsHttpEnvelopePolicy.MESSAGE_TYPE_HEADER

    private val objectMapper: ObjectMapper = ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    )

    fun parse(json: String, messageTypeHeader: String? = null): SnsHttpMessage =
        SnsHttpEnvelopePolicy.parse(
            json = json,
            messageTypeHeader = messageTypeHeader,
            maxMessageBytes = SnsHttpMessageLimits.MAX_BYTES,
            decoder = ::decodeObject,
        ).toSpringMessage()

    @Suppress("UNCHECKED_CAST")
    private fun decodeObject(json: String): Map<String, Any?> =
        objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
}

private fun SnsHttpEnvelope.toSpringMessage(): SnsHttpMessage =
    SnsHttpMessage(
        type = SnsHttpMessageType.from(type.value),
        messageId = messageId,
        topicArn = topicArn,
        message = message,
        timestamp = timestamp,
        signatureVersion = signatureVersion,
        signature = signature,
        signingCertUrl = signingCertUrl,
        subject = subject,
        token = token,
        subscribeUrl = subscribeUrl,
        unsubscribeUrl = unsubscribeUrl,
        raw = raw,
    )
