package io.bluetape4k.aws.ktor.sqs

import software.amazon.awssdk.services.sqs.model.Message
import kotlin.reflect.KClass

/**
 * Converts an AWS SQS [Message] into the payload type requested by a handler.
 *
 * Contract:
 * - Implementations must be thread-safe because handlers can run concurrently.
 * - Throw [IllegalArgumentException] when the target type is unsupported.
 *
 * ```kotlin
 * converter.convert(message, String::class)
 * ```
 */
interface SqsMessageConverter {

    /**
     * Converts [message] into [targetType].
     */
    fun <T: Any> convert(message: Message, targetType: KClass<T>): T
}

/**
 * Default converter for raw SQS message payloads.
 *
 * Supports [String], [ByteArray], and the raw AWS SDK [Message]. Use a custom
 * [SqsMessageConverter] for JSON or domain-specific decoding.
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
