package io.bluetape4k.aws.ktor.sqs

import software.amazon.awssdk.services.sqs.model.Message
import kotlin.reflect.KClass

/**
 * AWS SQS [Message]를 handler가 요구한 payload 타입으로 변환합니다.
 *
 * 계약:
 * - handler가 동시에 실행될 수 있으므로 구현체는 thread-safe 해야 합니다.
 * - 지원하지 않는 대상 타입이면 [IllegalArgumentException]을 던집니다.
 *
 * ```kotlin
 * converter.convert(message, String::class)
 * ```
 */
interface SqsMessageConverter {

    /**
     * [message]를 [targetType] 타입으로 변환합니다.
     */
    fun <T: Any> convert(message: Message, targetType: KClass<T>): T
}

/**
 * 원시 SQS 메시지 payload를 처리하는 기본 converter입니다.
 *
 * [String], [ByteArray], 원본 AWS SDK [Message]를 지원합니다. JSON 또는 도메인 전용
 * decoding이 필요하면 사용자 정의 [SqsMessageConverter]를 사용합니다.
 */
object StringOrByteArraySqsMessageConverter: SqsMessageConverter {

    @Suppress("UNCHECKED_CAST")
    override fun <T: Any> convert(message: Message, targetType: KClass<T>): T {
        val body = message.body().orEmpty()
        val converted: Any = when (targetType) {
            String::class  -> body
            ByteArray::class -> body.encodeToByteArray()
            Message::class -> message
            else           -> throw IllegalArgumentException(
                "Unsupported SQS message target type: ${targetType.qualifiedName}. " +
                    "Configure a custom SqsMessageConverter."
            )
        }
        return converted as T
    }
}
