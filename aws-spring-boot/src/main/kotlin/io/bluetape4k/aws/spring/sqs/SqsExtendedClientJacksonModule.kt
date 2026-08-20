@file:Suppress("MaxLineLength")

package io.bluetape4k.aws.spring.sqs

import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.ser.std.StdSerializer

/**
 * Extended Client public model을 raw AWS request/response와 분리하는 Jackson 3 module입니다.
 *
 * bucket, object key, pointer signature, receipt handle, encryption context, cleanup handle
 * 같은 내부 자격 증명·위치 정보는 serializer 결과에 포함하지 않습니다.
 */
class SqsExtendedClientJacksonModule : SimpleModule("SqsExtendedClientJacksonModule") {

    init {
        addSerializer(SqsExtendedSendRequest::class.java, SendRequestSerializer())
        addSerializer(SqsExtendedReceivedMessage::class.java, ReceivedMessageSerializer())
        addSerializer(SqsExtendedClientPointer::class.java, PointerSerializer())
        addSerializer(SqsExtendedSendResponse::class.java, SendResponseSerializer())
        addSerializer(SqsExtendedSendResult::class.java, SendResultSerializer())
        addSerializer(SqsExtendedAcknowledgementResult::class.java, AcknowledgementResultSerializer())
        addSerializer(SqsExtendedCleanupResult::class.java, CleanupResultSerializer())
        addSerializer(SqsExtendedCleanupHandle::class.java, CleanupHandleSerializer())
        addSerializer(SqsExtendedMessageAttribute::class.java, MessageAttributeSerializer())
    }
}

private abstract class ExtendedSerializer<T>(type: Class<T>) : StdSerializer<T>(type) {
    protected fun JsonGenerator.writeNullableString(name: String, value: String?) {
        writeName(name)
        if (value == null) writeNull() else writeString(value)
    }
}

private class SendRequestSerializer : ExtendedSerializer<SqsExtendedSendRequest>(SqsExtendedSendRequest::class.java) {
    override fun serialize(value: SqsExtendedSendRequest, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeStartObject()
        gen.writeBooleanProperty("contentTypePresent", value.contentType != null)
        gen.writeBooleanProperty("idempotencyKeyPresent", value.idempotencyKey != null)
        gen.writeEndObject()
    }
}

private class ReceivedMessageSerializer : ExtendedSerializer<SqsExtendedReceivedMessage>(SqsExtendedReceivedMessage::class.java) {
    override fun serialize(value: SqsExtendedReceivedMessage, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeStartObject()
        gen.writeStringProperty("body", value.body)
        gen.writeStringProperty("messageId", value.messageId)
        gen.writeNullableString("contentType", value.contentType)
        gen.writeBooleanProperty("duplicateAfterCleanup", value.duplicateAfterCleanup)
        gen.writeNumberProperty("messageAttributeCount", value.messageAttributes.size)
        gen.writeNumberProperty("systemAttributeCount", value.systemAttributes.size)
        gen.writeEndObject()
    }
}

private class PointerSerializer : ExtendedSerializer<SqsExtendedClientPointer>(SqsExtendedClientPointer::class.java) {
    override fun serialize(value: SqsExtendedClientPointer, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeStartObject()
        gen.writeNullableString("contentType", value.contentType)
        gen.writeBooleanProperty("encrypted", value.encrypted)
        gen.writeEndObject()
    }
}

private class SendResponseSerializer : ExtendedSerializer<SqsExtendedSendResponse>(SqsExtendedSendResponse::class.java) {
    override fun serialize(value: SqsExtendedSendResponse, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeStartObject()
        gen.writeNullableString("messageId", value.messageId)
        gen.writeNullableString("sequenceNumber", value.sequenceNumber)
        gen.writeNullableString("md5OfMessageBody", value.md5OfMessageBody)
        gen.writeNullableString("md5OfMessageAttributes", value.md5OfMessageAttributes)
        gen.writeEndObject()
    }
}

private class SendResultSerializer : ExtendedSerializer<SqsExtendedSendResult>(SqsExtendedSendResult::class.java) {
    override fun serialize(value: SqsExtendedSendResult, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeStartObject()
        gen.writeName("response")
        SendResponseSerializer().serialize(value.response, gen, ctxt)
        gen.writeBooleanProperty("offloaded", value.offloaded)
        value.pointer?.let { pointer ->
            gen.writeName("pointer")
            PointerSerializer().serialize(pointer, gen, ctxt)
        }
        gen.writeEndObject()
    }
}

private class AcknowledgementResultSerializer : ExtendedSerializer<SqsExtendedAcknowledgementResult>(SqsExtendedAcknowledgementResult::class.java) {
    override fun serialize(value: SqsExtendedAcknowledgementResult, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeStartObject()
        gen.writeBooleanProperty("sqsDeleted", value.sqsDeleted)
        gen.writeBooleanProperty("payloadDeleted", value.payloadDeleted)
        gen.writeBooleanProperty("cleanupRequired", value.cleanupRequired)
        gen.writeNullableString("failureKind", value.failureKind?.name)
        gen.writeBooleanProperty("retryable", value.retryable)
        gen.writeEndObject()
    }
}

private class CleanupResultSerializer : ExtendedSerializer<SqsExtendedCleanupResult>(SqsExtendedCleanupResult::class.java) {
    override fun serialize(value: SqsExtendedCleanupResult, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeStartObject()
        gen.writeBooleanProperty("deleted", value.deleted)
        gen.writeBooleanProperty("cleanupRequired", value.cleanupRequired)
        gen.writeNullableString("failureKind", value.failureKind?.name)
        gen.writeBooleanProperty("retryable", value.retryable)
        gen.writeNullableString("diagnosticCode", value.diagnosticCode)
        gen.writeEndObject()
    }
}

private class CleanupHandleSerializer : ExtendedSerializer<SqsExtendedCleanupHandle>(SqsExtendedCleanupHandle::class.java) {
    override fun serialize(value: SqsExtendedCleanupHandle, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeStartObject()
        gen.writeBooleanProperty("available", true)
        gen.writeEndObject()
    }
}

private class MessageAttributeSerializer : ExtendedSerializer<SqsExtendedMessageAttribute>(SqsExtendedMessageAttribute::class.java) {
    override fun serialize(value: SqsExtendedMessageAttribute, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeStartObject()
        gen.writeStringProperty("dataType", value.dataType)
        gen.writeNullableString("stringValue", value.stringValue)
        gen.writeBooleanProperty("binaryValuePresent", value.binaryValue != null)
        gen.writeEndObject()
    }
}
