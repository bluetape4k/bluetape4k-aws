package io.bluetape4k.aws.ktor.sns

import io.bluetape4k.aws.sns.SnsHttpEnvelope
import io.bluetape4k.aws.sns.SnsHttpEnvelopePolicy
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.ObjectMapper

/**
 * Amazon SNS HTTP(S) 엔드포인트 JSON 메시지 파서입니다.
 *
 * ## 계약
 *
 * JSON decode와 Ktor 공개 모델 변환만 담당하며, decoded envelope 구조는
 * [SnsHttpEnvelopePolicy]로 검증합니다. 구조 검증은 SNS 서명 검증이 아닙니다. 호출자는
 * 메시지를 신뢰하기 전에 인증서 체인, 서명, 예상 topic ARN과 재생 정책을 검증해야 합니다.
 */
class SnsHttpMessageParser(
    objectMapper: ObjectMapper = defaultObjectMapper(),
    private val maxMessageBytes: Int = SnsHttpEnvelopePolicy.MAX_MESSAGE_BYTES,
) {

    private val objectMapper: ObjectMapper = StrictObjectMapperSupport.enforce(objectMapper)

    init {
        require(maxMessageBytes > 0) { "maxMessageBytes must be positive." }
    }

    /** SNS HTTP JSON 본문을 신뢰되지 않은 [SnsHttpMessage]로 파싱합니다. */
    fun parse(json: String, messageTypeHeader: String? = null): SnsHttpMessage =
        SnsHttpEnvelopePolicy.parse(
            json = json,
            messageTypeHeader = messageTypeHeader,
            maxMessageBytes = maxMessageBytes,
            decoder = ::decodeObject,
        ).toKtorMessage()

    @Suppress("UNCHECKED_CAST")
    private fun decodeObject(json: String): Map<String, Any?> =
        objectMapper.readValue(json, Map::class.java) as Map<String, Any?>

    companion object {
        /** SNS HTTP 메시지 타입 헤더입니다. */
        const val MESSAGE_TYPE_HEADER: String = SnsHttpEnvelopePolicy.MESSAGE_TYPE_HEADER

        /** 중복 필드를 엄격하게 감지하는 기본 파서를 생성합니다. */
        fun default(): SnsHttpMessageParser = SnsHttpMessageParser()

        private fun defaultObjectMapper(): ObjectMapper =
            ObjectMapper(
                JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build()
            )
    }
}

private fun SnsHttpEnvelope.toKtorMessage(): SnsHttpMessage =
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
        raw = raw.mapValues { (_, value) -> value as? String },
    )
