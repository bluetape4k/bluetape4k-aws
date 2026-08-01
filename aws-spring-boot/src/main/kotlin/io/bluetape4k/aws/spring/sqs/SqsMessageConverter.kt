package io.bluetape4k.aws.spring.sqs

import tools.jackson.databind.ObjectMapper

/**
 * SQS 메시지 본문을 리스너 메서드 매개변수 타입으로 변환합니다.
 */
interface SqsMessageConverter {

    /**
     * [message]를 [targetType]으로 변환합니다.
     */
    fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any
}

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

    override fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any =
        objectMapper.readValue(message.body, targetType)
}
