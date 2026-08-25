package io.bluetape4k.aws.spring.sns

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHeaders
import software.amazon.awssdk.services.sns.model.MessageAttributeValue

/** Spring [Message] payload를 명시적인 suspend serializer로 문자열화합니다. */
public fun interface SnsPayloadSerializer {

    /** 네트워크 호출 없이 payload를 SNS message 문자열로 변환합니다. */
    public suspend fun serialize(payload: Any?): String
}

/** SNS Message batch 변환 전에 적용하는 유한 입력 상한입니다. */
public data class SnsBatchMessageConversionOptions(
    /** 허용하는 최대 메시지 수입니다. 1 이상이어야 합니다. */
    public val maxMessages: Int = 10_000,
)

/** SNS batch converter가 읽는 case-sensitive Spring header allowlist입니다. */
public object SnsBatchMessageHeaders {
    /** explicit entry ID source입니다. 값은 [java.util.UUID]여야 합니다. */
    public const val MESSAGE_ID: String = "bluetape4k.sns.messageId"

    /** SNS subject source입니다. 값은 non-blank [String]이어야 합니다. */
    public const val SUBJECT: String = "bluetape4k.sns.subject"

    /** typed SNS message attributes source입니다. */
    public const val MESSAGE_ATTRIBUTES: String = "bluetape4k.sns.messageAttributes"

    /** FIFO topic의 message group ID source입니다. */
    public const val MESSAGE_GROUP_ID: String = "MessageGroupId"

    /** FIFO topic의 message deduplication ID source입니다. */
    public const val MESSAGE_DEDUPLICATION_ID: String = "MessageDeduplicationId"
}

/** Spring Message를 SNS batch entry로 바꿀 때의 안전한 오류 분류입니다. */
public enum class SnsBatchMessageConversionError {
    INVALID_TOPIC,
    INVALID_OPTIONS,
    MISSING_ID,
    INVALID_ID_TYPE,
    INVALID_HEADER_TYPE,
    INVALID_ATTRIBUTES,
    INVALID_FIFO,
    SERIALIZATION_FAILED,
    ITERATION_FAILED,
    DUPLICATE_ID,
    TOO_MANY_MESSAGES,
}

/** 변환 오류에서 노출을 허용하는 header/payload field 분류입니다. */
public enum class SnsBatchMessageConversionField {
    MESSAGE_ID,
    SUBJECT,
    MESSAGE_ATTRIBUTES,
    MESSAGE_GROUP_ID,
    MESSAGE_DEDUPLICATION_ID,
    PAYLOAD,
}

/**
 * SNS Message 변환 실패를 cause-free safe contract로 전달합니다.
 *
 * payload, header map, topic ARN, credential, serializer 원인은 저장하지 않으며 문자열에는 enum,
 * entry index, allowlisted field만 남깁니다. [CancellationException]은 원래 instance로 전파됩니다.
 */
public class SnsBatchMessageConversionException(
    /** 실패한 collection entry index입니다. 전체 topic/options 오류면 null입니다. */
    public val entryIndex: Int?,
    /** 안정적인 변환 오류 분류입니다. */
    public val error: SnsBatchMessageConversionError,
    /** 실패한 allowlisted field입니다. 해당하지 않으면 null입니다. */
    public val field: SnsBatchMessageConversionField?,
) : IllegalArgumentException(
    "SNS batch message conversion failed: error=$error, entryIndex=$entryIndex, field=$field",
) {

    override fun toString(): String =
        "SnsBatchMessageConversionException(error=$error, entryIndex=$entryIndex, field=$field)"
}

/**
 * Spring Message를 typed SNS batch 요청으로 변환합니다.
 *
 * 기본 constructor는 String payload만 허용하며 구조화 payload는 명시적인 suspend serializer를
 * 요구합니다. converter는 SNS client나 network를 호출하지 않고 모든 entry 변환이 끝난 뒤에만
 * request를 생성합니다. `spring-messaging`은 compileOnly opt-in이므로 converter 사용자가 runtime
 * dependency를 직접 제공합니다.
 */
public class SnsBatchMessageConverter(
    /** 구조화 payload를 문자열화하는 호출자 소유의 suspend serializer입니다. */
    private val serializer: SnsPayloadSerializer,
) {

    /** String payload만 허용하는 기본 converter입니다. */
    public constructor() : this(SnsPayloadSerializer { payload ->
        require(payload is String) { "SNS batch payload must be String or use an explicit serializer." }
        payload
    })

    /** 단일 Spring Message를 typed SNS entry로 변환합니다. network 호출은 수행하지 않습니다. */
    public suspend fun convert(message: Message<*>): SnsPublishBatchEntry =
        convertMessage(message, entryIndex = null)

    /**
     * 유한 collection 전체를 변환한 뒤 typed SNS batch request를 반환합니다.
     *
     * `maxMessages`와 collection 크기를 serializer 호출 전에 검사하며, 중간 실패·취소 시 request를
     * 반환하지 않습니다. FIFO header와 ID 중복은 전체 request 규칙으로 검증합니다.
     */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught", "SwallowedException")
    public suspend fun convertAll(
        topicArn: String,
        messages: Collection<Message<*>>,
        options: SnsBatchMessageConversionOptions = SnsBatchMessageConversionOptions(),
    ): SnsPublishBatchRequest {
        if (topicArn.isBlank()) {
            throw conversion(null, SnsBatchMessageConversionError.INVALID_TOPIC, null)
        }
        if (options.maxMessages <= 0) {
            throw conversion(null, SnsBatchMessageConversionError.INVALID_OPTIONS, null)
        }

        val size = try {
            messages.size
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Error) {
            throw cause
        } catch (cause: RuntimeException) {
            throw conversion(null, SnsBatchMessageConversionError.ITERATION_FAILED, null)
        }
        if (size > options.maxMessages) {
            throw conversion(null, SnsBatchMessageConversionError.TOO_MANY_MESSAGES, null)
        }

        val entries = ArrayList<SnsPublishBatchEntry>(size)
        val ids = HashSet<String>(size)
        var index = 0
        try {
            for (message in messages) {
                currentCoroutineContext().ensureActive()
                val entry = convertMessage(message, index)
                if (!ids.add(entry.id)) {
                    throw conversion(index, SnsBatchMessageConversionError.DUPLICATE_ID, null)
                }
                entries += entry
                currentCoroutineContext().ensureActive()
                index++
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: SnsBatchMessageConversionException) {
            throw cause
        } catch (cause: Error) {
            throw cause
        } catch (cause: RuntimeException) {
            throw conversion(index, SnsBatchMessageConversionError.ITERATION_FAILED, null)
        }

        validateTopicRules(topicArn, entries)
        return SnsPublishBatchRequest(topicArn, entries)
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught", "SwallowedException")
    private suspend fun convertMessage(
        message: Message<*>,
        entryIndex: Int?,
    ): SnsPublishBatchEntry {
        currentCoroutineContext().ensureActive()
        val headers = message.headers
        val id = resolveId(headers, entryIndex)
        val subject = optionalString(
            headers,
            SnsBatchMessageHeaders.SUBJECT,
            entryIndex,
            SnsBatchMessageConversionField.SUBJECT,
        )
        val attributes = optionalAttributes(headers, entryIndex)
        val groupId = optionalString(
            headers,
            SnsBatchMessageHeaders.MESSAGE_GROUP_ID,
            entryIndex,
            SnsBatchMessageConversionField.MESSAGE_GROUP_ID,
        )
        val deduplicationId = optionalString(
            headers,
            SnsBatchMessageHeaders.MESSAGE_DEDUPLICATION_ID,
            entryIndex,
            SnsBatchMessageConversionField.MESSAGE_DEDUPLICATION_ID,
        )
        val serialized = serializePayload(message.payload, entryIndex)
        currentCoroutineContext().ensureActive()
        if (serialized.isBlank()) {
            throw conversion(
                entryIndex,
                SnsBatchMessageConversionError.SERIALIZATION_FAILED,
                SnsBatchMessageConversionField.PAYLOAD,
            )
        }
        if (groupId?.isBlank() == true || deduplicationId?.isBlank() == true) {
            throw conversion(
                entryIndex,
                SnsBatchMessageConversionError.INVALID_FIFO,
                SnsBatchMessageConversionField.MESSAGE_GROUP_ID,
            )
        }
        return buildEntry(
            id = id,
            serialized = serialized,
            subject = subject,
            attributes = attributes,
            groupId = groupId,
            deduplicationId = deduplicationId,
            entryIndex = entryIndex,
        )
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun serializePayload(payload: Any?, entryIndex: Int?): String = try {
        serializer.serialize(payload)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Error) {
        throw cause
    } catch (cause: RuntimeException) {
        throw conversion(
            entryIndex,
            SnsBatchMessageConversionError.SERIALIZATION_FAILED,
            SnsBatchMessageConversionField.PAYLOAD,
        )
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun buildEntry(
        id: String,
        serialized: String,
        subject: String?,
        attributes: Map<String, MessageAttributeValue>,
        groupId: String?,
        deduplicationId: String?,
        entryIndex: Int?,
    ): SnsPublishBatchEntry = try {
        SnsPublishBatchEntry(
            id = id,
            message = serialized,
            subject = subject,
            messageAttributes = attributes,
            messageGroupId = groupId,
            messageDeduplicationId = deduplicationId,
        )
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Error) {
        throw cause
    } catch (cause: RuntimeException) {
        throw conversion(
            entryIndex,
            SnsBatchMessageConversionError.SERIALIZATION_FAILED,
            SnsBatchMessageConversionField.PAYLOAD,
        )
    }

    private fun resolveId(headers: MessageHeaders, entryIndex: Int?): String {
        val explicit = headers.containsKey(SnsBatchMessageHeaders.MESSAGE_ID)
        val raw = if (explicit) headers[SnsBatchMessageHeaders.MESSAGE_ID] else headers[MessageHeaders.ID]
        if (raw == null) {
            throw conversion(
                entryIndex,
                if (explicit) {
                    SnsBatchMessageConversionError.INVALID_ID_TYPE
                } else {
                    SnsBatchMessageConversionError.MISSING_ID
                },
                SnsBatchMessageConversionField.MESSAGE_ID,
            )
        }
        if (raw !is java.util.UUID) {
            throw conversion(
                entryIndex,
                SnsBatchMessageConversionError.INVALID_ID_TYPE,
                SnsBatchMessageConversionField.MESSAGE_ID,
            )
        }
        return raw.toString()
    }

    private fun optionalString(
        headers: MessageHeaders,
        key: String,
        entryIndex: Int?,
        field: SnsBatchMessageConversionField,
    ): String? {
        if (!headers.containsKey(key)) {
            return null
        }
        val value = headers[key]
        if (value !is String || value.isBlank()) {
            throw conversion(entryIndex, SnsBatchMessageConversionError.INVALID_HEADER_TYPE, field)
        }
        return value
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught", "SwallowedException")
    private fun optionalAttributes(
        headers: MessageHeaders,
        entryIndex: Int?,
    ): Map<String, MessageAttributeValue> {
        if (!headers.containsKey(SnsBatchMessageHeaders.MESSAGE_ATTRIBUTES)) {
            return emptyMap()
        }
        val raw = headers[SnsBatchMessageHeaders.MESSAGE_ATTRIBUTES]
        if (raw !is Map<*, *>) {
            throw conversion(
                entryIndex,
                SnsBatchMessageConversionError.INVALID_ATTRIBUTES,
                SnsBatchMessageConversionField.MESSAGE_ATTRIBUTES,
            )
        }
        return try {
            raw.entries.associate { (key, value) ->
                if (key !is String || key.isBlank() || value !is MessageAttributeValue) {
                    throw conversion(
                        entryIndex,
                        SnsBatchMessageConversionError.INVALID_ATTRIBUTES,
                        SnsBatchMessageConversionField.MESSAGE_ATTRIBUTES,
                    )
                }
                key to value
            }.toMap()
        } catch (cause: SnsBatchMessageConversionException) {
            throw cause
        } catch (cause: RuntimeException) {
            throw conversion(
                entryIndex,
                SnsBatchMessageConversionError.INVALID_ATTRIBUTES,
                SnsBatchMessageConversionField.MESSAGE_ATTRIBUTES,
            )
        }
    }

    private fun validateTopicRules(topicArn: String, entries: List<SnsPublishBatchEntry>) {
        val fifo = topicArn.endsWith(".fifo")
        entries.forEachIndexed { index, entry ->
            if (fifo && entry.messageGroupId.isNullOrBlank()) {
                throw conversion(
                    index,
                    SnsBatchMessageConversionError.INVALID_FIFO,
                    SnsBatchMessageConversionField.MESSAGE_GROUP_ID,
                )
            }
            if (!fifo && (entry.messageGroupId != null || entry.messageDeduplicationId != null)) {
                throw conversion(
                    index,
                    SnsBatchMessageConversionError.INVALID_FIFO,
                    SnsBatchMessageConversionField.MESSAGE_GROUP_ID,
                )
            }
        }
    }

    private fun conversion(
        entryIndex: Int?,
        error: SnsBatchMessageConversionError,
        field: SnsBatchMessageConversionField?,
    ) = SnsBatchMessageConversionException(entryIndex, error, field)
}
