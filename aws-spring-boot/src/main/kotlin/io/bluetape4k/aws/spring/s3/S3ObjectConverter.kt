package io.bluetape4k.aws.spring.s3

import tools.jackson.databind.ObjectMapper

/** S3 객체 payload와 도메인 객체 사이의 typed 변환 계약입니다. */
interface S3ObjectConverter<T : Any> {

    /** [value]를 S3에 저장할 바이트로 직렬화합니다. */
    fun write(value: T): ByteArray

    /** [bytes]를 [targetType]으로 역직렬화합니다. */
    fun read(bytes: ByteArray, targetType: Class<out T>): T

    /** 이 converter가 기본으로 제안하는 Content-Type입니다. */
    val contentType: String?
        get() = null
}

/** Jackson 3 [ObjectMapper]를 사용하는 기본 S3 객체 converter입니다. */
class JacksonS3ObjectConverter(
    private val objectMapper: ObjectMapper,
    defaultContentType: String? = "application/json",
) : S3ObjectConverter<Any> {

    override val contentType: String? = defaultContentType

    override fun write(value: Any): ByteArray = objectMapper.writeValueAsBytes(value)

    @Suppress("UNCHECKED_CAST")
    override fun read(bytes: ByteArray, targetType: Class<out Any>): Any =
        objectMapper.readValue(bytes, targetType)
}
