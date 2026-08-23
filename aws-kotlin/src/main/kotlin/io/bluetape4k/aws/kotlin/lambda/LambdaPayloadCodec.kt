package io.bluetape4k.aws.kotlin.lambda

import tools.jackson.databind.ObjectMapper

/** Lambda payload를 SDK 바이트와 호출자 값 사이에서 변환하는 codec입니다. */
interface LambdaPayloadCodec<T> {

    /** 값을 Lambda request payload 바이트로 인코딩합니다. */
    fun encode(value: T): ByteArray

    /** Lambda response payload 바이트를 값으로 디코딩합니다. */
    fun decode(payload: ByteArray): T
}

/** 기본 Lambda payload codec 모음입니다. */
object LambdaPayloadCodecs {

    /** 원시 바이트 payload를 복사 기반으로 처리합니다. */
    val bytes: LambdaPayloadCodec<ByteArray> = object : LambdaPayloadCodec<ByteArray> {
        override fun encode(value: ByteArray): ByteArray = value.copyOf()

        override fun decode(payload: ByteArray): ByteArray = payload.copyOf()
    }

    /** UTF-8 문자열 payload를 처리합니다. */
    val utf8: LambdaPayloadCodec<String> = object : LambdaPayloadCodec<String> {
        override fun encode(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

        override fun decode(payload: ByteArray): String = payload.toString(Charsets.UTF_8)
    }

    /** caller가 제공한 Jackson 3 mapper와 구체적인 대상 타입을 연결합니다. */
    fun <T> jackson(
        objectMapper: ObjectMapper,
        valueType: Class<T>,
    ): LambdaPayloadCodec<T> = object : LambdaPayloadCodec<T> {
        override fun encode(value: T): ByteArray = objectMapper.writeValueAsBytes(value)

        override fun decode(payload: ByteArray): T = objectMapper.readValue(payload, valueType)
    }
}
