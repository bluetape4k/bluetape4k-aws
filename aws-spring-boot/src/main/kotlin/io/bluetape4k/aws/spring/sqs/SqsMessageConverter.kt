package io.bluetape4k.aws.spring.sqs

import tools.jackson.databind.ObjectMapper
import java.lang.reflect.Type

/**
 * SQS 메시지 본문을 리스너 메서드 매개변수 타입으로 변환합니다.
 */
interface SqsMessageConverter {

    /**
     * [message]를 [targetType]으로 변환합니다.
     */
    fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any

    /**
     * generic listener parameter 타입을 보존한 변환 진입점입니다.
     *
     * 기존 converter 구현은 두 인자 [convert]만 구현해도 동작하며, generic 타입을 지원하는
     * converter만 이 overload를 재정의할 수 있습니다.
     */
    fun convert(message: SqsReceivedMessage, targetType: Class<*>, genericType: Type?): Any =
        convert(message, targetType)
}

/**
 * batch 항목 변환 실패를 index와 concrete target type으로 표현합니다.
 */
class SqsMessageConversionException(
    val index: Int,
    val targetType: Class<*>,
    cause: Throwable,
) : IllegalArgumentException("SQS message conversion failed at batch index $index for ${targetType.name}", cause)

/**
 * 네이티브가 아닌 리스너 매개변수 타입에서 실패하는 변환기입니다.
 */
object NoopSqsMessageConverter: SqsMessageConverter {
    override fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any =
        throw IllegalArgumentException("Unsupported @SqsListener parameter type: ${targetType.name}")
}

/**
 * Jackson 기반 SQS 메시지 본문 변환기입니다.
 */
class JacksonSqsMessageConverter(
    private val objectMapper: ObjectMapper,
) : SqsMessageConverter {

    private val delegate: SnsMessageConverter by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SnsMessageConverter(objectMapper)
    }

    override fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any =
        delegate.convert(message, targetType)

    override fun convert(message: SqsReceivedMessage, targetType: Class<*>, genericType: Type?): Any =
        delegate.convert(message, targetType, genericType)
}
