package io.bluetape4k.aws.spring.sqs

import tools.jackson.databind.ObjectMapper

/**
 * Converts an SQS message body to a listener method parameter type.
 */
interface SqsMessageConverter {

    /**
     * Converts [message] to [targetType].
     */
    fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any
}

/**
 * Converter that fails for non-native listener parameter types.
 */
object NoopSqsMessageConverter: SqsMessageConverter {
    override fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any =
        throw IllegalArgumentException("Unsupported @SqsListener parameter type: ${targetType.name}")
}

/**
 * Jackson-backed SQS message body converter.
 */
class JacksonSqsMessageConverter(
    private val objectMapper: ObjectMapper,
) : SqsMessageConverter {

    override fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any =
        objectMapper.readValue(message.body, targetType)
}
