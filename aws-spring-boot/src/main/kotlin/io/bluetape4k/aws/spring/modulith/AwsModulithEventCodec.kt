package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireBeTrue
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.toUtf8Bytes
import org.springframework.modulith.events.core.EventSerializer
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CancellationException

private const val SPEC_VERSION = 1
private const val DEFAULT_MAX_SERIALIZED_PAYLOAD_BYTES = 196_608
private const val DEFAULT_MAX_ENVELOPE_BYTES = 262_144
private const val MAX_DEPTH = 32
private const val MAX_TOKEN_COUNT = 100_000L
private const val MAX_STRING_BYTES = 196_608
private const val MAX_NUMBER_LENGTH = 1_000
private const val MAX_ATTRIBUTE_COUNT = 10
private const val SYSTEM_ATTRIBUTE_COUNT = 3
private const val MAX_HEADER_VALUE_BYTES = 1_024
private const val MAX_EVENT_ID_LENGTH = 128
private const val MAX_EVENT_ID_BYTES = 128
private const val SYSTEM_EVENT_ID_VALUE = "bt4k-event-id"
private const val SYSTEM_EVENT_TYPE_VALUE = "bt4k-event-type"
private const val SYSTEM_EVENT_VERSION_VALUE = "bt4k-event-version"
private const val FIELD_SPEC_VERSION = "specVersion"
private const val FIELD_ID = "id"
private const val FIELD_TYPE = "type"
private const val FIELD_VERSION = "version"
private const val FIELD_PAYLOAD = "payload"
private const val FIELD_HEADERS = "headers"

private val ENVELOPE_FIELDS = setOf(
    FIELD_SPEC_VERSION,
    FIELD_ID,
    FIELD_TYPE,
    FIELD_VERSION,
    FIELD_PAYLOAD,
    FIELD_HEADERS,
)
private val FORBIDDEN_TYPE_IDS = setOf("@class", "@type", "@c", "javaClass")
private val HEADER_NAME_PATTERN = Regex("[A-Za-z0-9_.-]{1,128}")
private val SENSITIVE_HEADER_PARTS = setOf(
    "authorization",
    "cookie",
    "credential",
    "password",
    "secret",
    "token",
)

/** envelope를 AWS message와 local event 사이에서 변환하는 내부 계약입니다. */
internal interface AwsModulithEventCodec {
    fun encode(event: Any): AwsModulithEncodedEvent

    fun decode(body: String, attributes: Map<String, String>): Any
}

/**
 * Spring Modulith serializer를 concrete registry class에만 연결하는 bounded codec입니다.
 *
 * codec은 AWS publisher나 client를 소유하지 않으며, 모든 입력은 publisher/dispatch 전에
 * byte·JSON·header 정책을 통과해야 합니다.
 */
internal class DefaultAwsModulithEventCodec(
    private val registry: AwsModulithEventTypeRegistry,
    private val eventSerializer: EventSerializer,
    private val maxSerializedPayloadBytes: Int = DEFAULT_MAX_SERIALIZED_PAYLOAD_BYTES,
    private val maxEnvelopeBytes: Int = DEFAULT_MAX_ENVELOPE_BYTES,
): AwsModulithEventCodec {

    companion object: KLogging() {
        const val SYSTEM_EVENT_ID: String = SYSTEM_EVENT_ID_VALUE
        const val SYSTEM_EVENT_TYPE: String = SYSTEM_EVENT_TYPE_VALUE
        const val SYSTEM_EVENT_VERSION: String = SYSTEM_EVENT_VERSION_VALUE
    }

    // Jackson 3 의 JsonMapper
    private val jsonMapper: JsonMapper = CodecJsonSupport.strictJsonMapper(maxSerializedPayloadBytes)

    init {
        maxSerializedPayloadBytes.requirePositiveNumber("maxSerializedPayloadBytes")
        maxEnvelopeBytes.requirePositiveNumber("maxEnvelopeBytes")
    }

    override fun encode(event: Any): AwsModulithEncodedEvent {
        val registration = registry.registrationFor(event)
        val eventId = registration.eventId(event)
        val headers = CodecHeaderPolicy.validateOutboundHeaders(registration, registration.headers(event))
        val serialized = serialize(event)
        if (CodecJsonSupport.utf8Size(serialized) > maxSerializedPayloadBytes) {
            throw AwsModulithOutboundEnvelopeException()
        }

        val envelope = AwsModulithEventEnvelope(
            id = eventId,
            type = registration.type,
            version = registration.version,
            payload = serialized,
            headers = headers.toMap(),
        )
        val body = CodecFailureSanitizer.sanitizeOutbound { jsonMapper.writeValueAsString(envelope) }
        if (CodecJsonSupport.utf8Size(body) > maxEnvelopeBytes) {
            throw AwsModulithOutboundEnvelopeException()
        }

        val attributes = LinkedHashMap<String, String>(headers.size + SYSTEM_ATTRIBUTE_COUNT)
        attributes.putAll(headers)
        attributes[SYSTEM_EVENT_ID] = eventId
        attributes[SYSTEM_EVENT_TYPE] = registration.type
        attributes[SYSTEM_EVENT_VERSION] = registration.version.toString()

        return AwsModulithEncodedEvent(body = body, messageAttributes = attributes.toMap())
    }

    override fun decode(body: String, attributes: Map<String, String>): Any = CodecFailureSanitizer.sanitizeInbound {
        val envelope = CodecJsonSupport.parseEnvelope(
            jsonMapper,
            maxSerializedPayloadBytes,
            maxEnvelopeBytes,
            body,
        )
        CodecHeaderPolicy.validateAttributes(attributes)
        CodecHeaderPolicy.validateSystemAttributes(envelope, attributes)

        val registration = registry.registrationFor(envelope.type, envelope.version)
        val headers = CodecHeaderPolicy.validateInboundHeaders(registration, envelope.headers)
        CodecHeaderPolicy.validateBodyHeadersAgainstAttributes(headers, attributes)
        CodecJsonSupport.preflightPayload(jsonMapper, maxSerializedPayloadBytes, envelope.payload)

        val decoded = deserialize(envelope.payload, registration.eventClass)
        if (decoded == null || decoded.javaClass != registration.eventClass) {
            throw AwsModulithEventRegistrationMismatchException()
        }
        decoded
    }

    private fun serialize(event: Any): String {
        val serialized = CodecFailureSanitizer.sanitizeOutbound { eventSerializer.serialize(event) }
        return serialized as? String ?: throw AwsModulithOutboundEnvelopeException()
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserialize(payload: String, eventClass: Class<*>): Any? {
        val concreteClass = eventClass as Class<Any>
        return CodecFailureSanitizer.sanitizeInbound {
            eventSerializer.deserialize(payload, concreteClass)
        }
    }
}

private object CodecJsonSupport: KLogging() {
    fun parseEnvelope(
        objectMapper: ObjectMapper,
        maxSerializedPayloadBytes: Int,
        maxEnvelopeBytes: Int,
        body: String,
    ): ParsedEnvelope = AwsModulithEnvelopeParser(
        objectMapper,
        maxSerializedPayloadBytes,
        maxEnvelopeBytes,
    ).parse(body)

    fun preflightPayload(objectMapper: ObjectMapper, maxSerializedPayloadBytes: Int, payload: String) {
        if (payload.isEmpty() || utf8Size(payload) > maxSerializedPayloadBytes) {
            throw AwsModulithInboundEnvelopeException()
        }
        val parser = CodecFailureSanitizer.sanitizeInbound { objectMapper.createParser(payload) }
        parser.use {
            StrictJsonReader(it).preflightPayload()
        }
    }

    fun strictJsonMapper(maxSerializedPayloadBytes: Int): JsonMapper {
        val constraints = StreamReadConstraints.builder()
            .maxNestingDepth(MAX_DEPTH)
            .maxTokenCount(MAX_TOKEN_COUNT)
            .maxNumberLength(MAX_NUMBER_LENGTH)
            .maxStringLength(maxOf(MAX_STRING_BYTES, maxSerializedPayloadBytes))
            .maxNameLength(MAX_STRING_BYTES)
            .build()
        val factory: JsonFactory = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(constraints)
            .build()

        return JsonMapper.builder(factory)
            .findAndAddModules()
            .build()
            .apply {
                log.info { "Initialize JsonMapper." }
            }
    }

    fun utf8Size(value: String): Int = value.toUtf8Bytes().size
}

private class AwsModulithEnvelopeParser(
    private val objectMapper: ObjectMapper,
    private val maxSerializedPayloadBytes: Int,
    private val maxEnvelopeBytes: Int,
) {
    fun parse(body: String): ParsedEnvelope {
        if (body.isBlank() || CodecJsonSupport.utf8Size(body) > maxEnvelopeBytes) {
            throw AwsModulithInboundEnvelopeException()
        }

        val parser = CodecFailureSanitizer.sanitizeInbound { objectMapper.createParser(body) }
        parser.use {
            val reader = StrictJsonReader(it)
            if (reader.next() != JsonToken.START_OBJECT) {
                throw AwsModulithInboundEnvelopeException()
            }
            val values = readFields(reader)
            return values.toParsedEnvelope()
        }
    }

    @Suppress("ThrowsCount")
    private fun readFields(reader: StrictJsonReader): MutableEnvelopeFields {
        val values = MutableEnvelopeFields()
        val fields = HashSet<String>(ENVELOPE_FIELDS.size)

        while (true) {
            when (val token = reader.next() ?: throw AwsModulithInboundEnvelopeException()) {
                JsonToken.PROPERTY_NAME -> readField(reader, fields, values)
                JsonToken.END_OBJECT -> break
                else -> throw AwsModulithInboundEnvelopeException()
            }
        }
        if (reader.next() != null || fields != ENVELOPE_FIELDS) {
            throw AwsModulithInboundEnvelopeException()
        }
        return values
    }

    private fun readField(
        reader: StrictJsonReader,
        fields: MutableSet<String>,
        values: MutableEnvelopeFields,
    ) {
        val name = reader.propertyName()
        if (!fields.add(name) || name !in ENVELOPE_FIELDS) {
            throw AwsModulithInboundEnvelopeException()
        }
        when (name) {
            FIELD_SPEC_VERSION -> values.specVersion = reader.requiredInteger()
            FIELD_ID -> values.id = reader.requiredString()
            FIELD_TYPE -> values.type = reader.requiredString()
            FIELD_VERSION -> values.version = reader.requiredInteger()
            FIELD_PAYLOAD -> values.payload = reader.requiredPayload(maxSerializedPayloadBytes)
            FIELD_HEADERS -> values.headers = reader.requiredHeaders()
        }
    }
}

private class MutableEnvelopeFields {
    var specVersion: Int? = null
    var id: String? = null
    var type: String? = null
    var version: Int? = null
    var payload: String? = null
    var headers: Map<String, String>? = null

    fun toParsedEnvelope(): ParsedEnvelope {
        specVersion.requireEquals(SPEC_VERSION, "specVersion")
        val eventId = id.requireNotNull("id")
        val eventType = type.requireNotNull("type")
        val eventVersion = version.requireNotNull("version")
        val eventPayload = payload.requireNotNull("payload")
        val eventHeaders = headers.requireNotNull("headers")
        CodecEventIdPolicy.isValidEventId(eventId).requireBeTrue("valid evnetId")
        eventType.requireNotBlank("eventType")
        eventVersion.requirePositiveNumber("eventVersion")

        return ParsedEnvelope(
            id = eventId,
            type = eventType,
            version = eventVersion,
            payload = eventPayload,
            headers = eventHeaders,
        )
    }

    override fun toString(): String =
        ToStringBuilder(this)
            .add("specVersion", specVersion)
            .add("id", id)
            .add("type", type)
            .add("version", version)
            .add("payload", payload)
            .add("headers", headers)
            .toString()
}

private data class ParsedEnvelope(
    val id: String,
    val type: String,
    val version: Int,
    val payload: String,
    val headers: Map<String, String>,
)

private object CodecHeaderPolicy {
    fun validateOutboundHeaders(
        registration: AwsModulithResolvedRegistration,
        headers: Map<String, String>,
    ): Map<String, String> = validatedHeaders(registration, headers)
        ?: throw AwsModulithOutboundEnvelopeException()

    fun validateInboundHeaders(
        registration: AwsModulithResolvedRegistration,
        headers: Map<String, String>,
    ): Map<String, String> = validatedHeaders(registration, headers)
        ?: throw AwsModulithInboundEnvelopeException()

    private fun validatedHeaders(
        registration: AwsModulithResolvedRegistration,
        headers: Map<String, String>,
    ): Map<String, String>? {
        if (headers.size + SYSTEM_ATTRIBUTE_COUNT > MAX_ATTRIBUTE_COUNT) {
            return null
        }
        return headers
            .takeIf { candidate ->
                candidate.all { (name, value) ->
                    name in registration.allowedHeaderNames &&
                            isBusinessHeaderName(name) &&
                            isValidHeaderValue(value)
                }
            }
            ?.let { LinkedHashMap(it).toMap() }
    }

    fun validateAttributes(attributes: Map<String, String>) {
        attributes.size.requireLe(MAX_ATTRIBUTE_COUNT, "attributes.size")
        attributes.forEach { (name, value) ->
            (isSystemAttribute(name) || isBusinessHeaderName(name)).requireBeTrue {
                "name must be system attribute or business header name."
            }
            isValidHeaderValue(value).requireBeTrue { "value[$value] must be valid header value." }
        }
    }

    fun validateSystemAttributes(envelope: ParsedEnvelope, attributes: Map<String, String>) {
        val expected = mapOf(
            DefaultAwsModulithEventCodec.SYSTEM_EVENT_ID to envelope.id,
            DefaultAwsModulithEventCodec.SYSTEM_EVENT_TYPE to envelope.type,
            DefaultAwsModulithEventCodec.SYSTEM_EVENT_VERSION to envelope.version.toString(),
        )
        expected.forEach { (name, value) ->
            attributes[name]?.let { supplied ->
                require(supplied == value)
            }
        }
    }

    fun validateBodyHeadersAgainstAttributes(
        headers: Map<String, String>,
        attributes: Map<String, String>,
    ) {
        headers.forEach { (name, value) ->
            attributes[name]?.let { supplied ->
                require(supplied == value)
            }
        }
    }

    private fun isSystemAttribute(name: String): Boolean =
        name == SYSTEM_EVENT_ID_VALUE || name == SYSTEM_EVENT_TYPE_VALUE || name == SYSTEM_EVENT_VERSION_VALUE

    private fun isBusinessHeaderName(name: String): Boolean {
        if (!HEADER_NAME_PATTERN.matches(name) || name.startsWith("bt4k-", ignoreCase = true)) {
            return false
        }
        val lowercase = name.lowercase()
        return SENSITIVE_HEADER_PARTS.none(lowercase::contains)
    }

    private fun isValidHeaderValue(value: String): Boolean =
        value.isNotEmpty() && CodecJsonSupport.utf8Size(value) <= MAX_HEADER_VALUE_BYTES
}

private class StrictJsonReader(
    private val parser: JsonParser,
) {
    var depth: Int = 0
        private set
    private var tokenCount: Long = 0

    @Suppress("ThrowsCount")
    fun next(): JsonToken? {
        val token = parser.nextToken() ?: return null
        tokenCount++
        if (tokenCount > MAX_TOKEN_COUNT) {
            throw AwsModulithInboundEnvelopeException()
        }
        when (token) {
            JsonToken.START_OBJECT, JsonToken.START_ARRAY -> {
                depth++
                if (depth > MAX_DEPTH) {
                    throw AwsModulithInboundEnvelopeException()
                }
            }

            JsonToken.END_OBJECT, JsonToken.END_ARRAY -> {
                depth--
                if (depth < 0) {
                    throw AwsModulithInboundEnvelopeException()
                }
            }

            else -> Unit
        }
        return token
    }

    fun propertyName(): String {
        val name = parser.currentName() ?: throw AwsModulithInboundEnvelopeException()
        validateText(name)
        return name
    }

    fun text(): String = parser.getString()

    fun validateText(value: String) {
        if (CodecJsonSupport.utf8Size(value) > MAX_STRING_BYTES) {
            throw AwsModulithInboundEnvelopeException()
        }
    }

    fun validateNumber(value: String) {
        if (value.length > MAX_NUMBER_LENGTH) {
            throw AwsModulithInboundEnvelopeException()
        }
    }

    fun requiredString(): String {
        if (next() != JsonToken.VALUE_STRING) {
            throw AwsModulithInboundEnvelopeException()
        }
        return text().also(::validateText)
    }

    fun requiredInteger(): Int {
        if (next() != JsonToken.VALUE_NUMBER_INT) {
            throw AwsModulithInboundEnvelopeException()
        }
        val value = text()
        validateNumber(value)
        return value.toIntOrNull() ?: throw AwsModulithInboundEnvelopeException()
    }

    fun requiredPayload(maxSerializedPayloadBytes: Int): String {
        if (next() != JsonToken.VALUE_STRING) {
            throw AwsModulithInboundEnvelopeException()
        }
        return text().also {
            if (CodecJsonSupport.utf8Size(it) > maxSerializedPayloadBytes) {
                throw AwsModulithInboundEnvelopeException()
            }
        }
    }

    @Suppress("ThrowsCount")
    fun requiredHeaders(): Map<String, String> {
        if (next() != JsonToken.START_OBJECT) {
            throw AwsModulithInboundEnvelopeException()
        }
        val headers = LinkedHashMap<String, String>()
        while (true) {
            when (val token = next() ?: throw AwsModulithInboundEnvelopeException()) {
                JsonToken.PROPERTY_NAME -> {
                    val name = propertyName()
                    val value = requiredString()
                    if (headers.containsKey(name)) {
                        throw AwsModulithInboundEnvelopeException()
                    }
                    headers[name] = value
                }

                JsonToken.END_OBJECT -> return headers.toMap()
                else -> throw AwsModulithInboundEnvelopeException()
            }
        }
    }

    fun preflightPayload() {
        val first = next() ?: throw AwsModulithInboundEnvelopeException()
        require(first != JsonToken.PROPERTY_NAME)
        require(first != JsonToken.END_OBJECT)
        require(first != JsonToken.END_ARRAY)
        validatePayloadToken(first)
        var rootCompleted = first != JsonToken.START_OBJECT && first != JsonToken.START_ARRAY
        while (true) {
            val token = next() ?: break
            require(!rootCompleted)
            validatePayloadToken(token)
            if (depth == 0) {
                rootCompleted = true
            }
        }
        require(rootCompleted && depth == 0)
    }

    private fun validatePayloadToken(token: JsonToken) {
        when (token) {
            JsonToken.PROPERTY_NAME -> {
                val name = propertyName()
                require(name !in FORBIDDEN_TYPE_IDS)
            }

            JsonToken.VALUE_STRING -> validateText(text())
            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> validateNumber(text())
            else -> Unit
        }
    }
}

private object CodecEventIdPolicy {
    fun isValidEventId(value: String): Boolean =
        value.isNotBlank() &&
                value.length <= MAX_EVENT_ID_LENGTH &&
                CodecJsonSupport.utf8Size(value) <= MAX_EVENT_ID_BYTES &&
                value.none(Char::isISOControl)
}

private object CodecFailureSanitizer {
    @Suppress("TooGenericExceptionCaught")
    inline fun <T> sanitizeOutbound(block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        rethrowOrOutbound(error)
    }

    @Suppress("TooGenericExceptionCaught")
    inline fun <T> sanitizeInbound(block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        rethrowOrInbound(error)
    }

    @Suppress("ThrowsCount")
    private fun rethrowOrOutbound(error: Throwable): Nothing {
        when (error) {
            is CancellationException -> throw error
            is Error -> throw error
            is AwsModulithEventException -> throw error
            else -> throw AwsModulithOutboundEnvelopeException()
        }
    }

    @Suppress("ThrowsCount")
    private fun rethrowOrInbound(error: Throwable): Nothing {
        when (error) {
            is CancellationException -> throw error
            is Error -> throw error
            is AwsModulithEventException -> throw error
            else -> throw AwsModulithInboundEnvelopeException()
        }
    }
}
