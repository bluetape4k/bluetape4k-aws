package io.bluetape4k.aws.spring.modulith

import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/**
 * 외부화할 Spring Modulith 이벤트 형식과 식별자 추출 규칙을 등록합니다.
 *
 * @property type 외부 envelope에서 사용하는 안정적인 이벤트 형식 이름
 * @property version 이벤트 payload 형식 버전
 * @property eventClass 등록할 이벤트의 정확한 JVM class
 * @property eventId 이벤트에서 안정적인 식별자를 추출하는 함수
 * @property allowedHeaderNames 외부 envelope에 포함할 수 있는 header 이름
 * @property headers 이벤트에서 header 값을 추출하는 함수
 */
data class AwsModulithEventTypeRegistration<T : Any>(
    val type: String,
    val version: Int,
    val eventClass: Class<T>,
    val eventId: (T) -> String,
    val allowedHeaderNames: Set<String> = emptySet(),
    val headers: (T) -> Map<String, String> = { emptyMap() },
)

/**
 * 외부화할 concrete event class와 안정적인 type/version 계약을 보관하는 immutable registry입니다.
 *
 * 동일 class 또는 type을 중복 등록할 수 없으며, 등록하지 않은 subtype이나 version을 자동으로
 * 추론하지 않습니다. 애플리케이션은 producer와 consumer가 공유하는 registry bean을 명시적으로
 * 제공해야 합니다.
 */
class AwsModulithEventTypeRegistry private constructor(
    registrations: List<AwsModulithEventTypeRegistration<*>>,
) {
    private val registrations: List<AwsModulithEventTypeRegistration<*>> = registrations.map {
        it.snapshot()
    }
    private val byEventClass: Map<Class<*>, AwsModulithResolvedRegistration>
    private val byType: Map<RegistrationKey, AwsModulithResolvedRegistration>
    private val registeredTypes: Set<String>

    init {
        if (this.registrations.size > MAX_REGISTRATIONS) {
            throw AwsModulithConfigurationException()
        }

        val resolved = this.registrations.map { registration ->
            validateRegistration(registration)
            @Suppress("UNCHECKED_CAST")
            val eventIdExtractor = registration.eventId as (Any) -> String
            @Suppress("UNCHECKED_CAST")
            val headersExtractor = registration.headers as (Any) -> Map<String, String>
            AwsModulithResolvedRegistration(
                type = registration.type,
                version = registration.version,
                eventClass = registration.eventClass,
                allowedHeaderNames = registration.allowedHeaderNames,
                eventIdExtractor = eventIdExtractor,
                headersExtractor = headersExtractor,
            )
        }

        val duplicateClass = resolved.groupingBy { it.eventClass }.eachCount().any { it.value > 1 }
        if (duplicateClass) {
            throw AwsModulithConfigurationException()
        }

        val duplicateType = resolved.groupingBy { it.type }.eachCount().any { it.value > 1 }
        if (duplicateType) {
            throw AwsModulithConfigurationException()
        }

        byEventClass = resolved.associateBy { it.eventClass }.toMap()
        byType = resolved.associateBy { RegistrationKey(it.type, it.version) }.toMap()
        registeredTypes = resolved.map { it.type }.toSet()
    }

    /** 원본 객체의 정확한 JVM class에 해당하는 등록을 조회합니다. */
    internal fun registrationFor(event: Any): AwsModulithResolvedRegistration =
        byEventClass[event.javaClass] ?: throw AwsModulithEventRegistrationMismatchException()

    /** 외부 envelope의 type/version 쌍에 해당하는 등록을 조회합니다. */
    internal fun registrationFor(type: String, version: Int): AwsModulithResolvedRegistration {
        if (type !in registeredTypes) {
            throw AwsModulithUnknownEventTypeException()
        }
        return byType[RegistrationKey(type, version)]
            ?: throw AwsModulithUnsupportedEventVersionException()
    }

    companion object {
        private const val MAX_REGISTRATIONS = 256

        /** 주어진 등록 항목으로 registry를 만듭니다. */
        fun of(vararg registrations: AwsModulithEventTypeRegistration<*>): AwsModulithEventTypeRegistry =
            AwsModulithEventTypeRegistry(registrations.toList())
    }

    private data class RegistrationKey(val type: String, val version: Int)
}

/** Registry가 내부 transport에 제공하는 검증된 이벤트 등록입니다. */
internal class AwsModulithResolvedRegistration internal constructor(
    val type: String,
    val version: Int,
    val eventClass: Class<*>,
    allowedHeaderNames: Set<String>,
    private val eventIdExtractor: (Any) -> String,
    private val headersExtractor: (Any) -> Map<String, String>,
) {
    val allowedHeaderNames: Set<String> = allowedHeaderNames.toSet()

    internal fun eventId(event: Any): String {
        val typedEvent = cast(event)
        return sanitizeExtractorFailure {
            @Suppress("UNCHECKED_CAST")
            val eventId = (eventIdExtractor as (Any) -> String)(typedEvent)
            if (!eventId.isValidAwsModulithEventId()) {
                throw AwsModulithEventRegistrationMismatchException()
            }
            eventId
        }
    }

    internal fun headers(event: Any): Map<String, String> {
        val typedEvent = cast(event)
        return sanitizeExtractorFailure {
            @Suppress("UNCHECKED_CAST")
            val headers = (headersExtractor as (Any) -> Map<String, String>)(typedEvent)
            if (headers.keys.any { it !in allowedHeaderNames }) {
                throw AwsModulithEventRegistrationMismatchException()
            }
            headers.toMap()
        }
    }

    private fun cast(event: Any): Any {
        if (event.javaClass != eventClass) {
            throw AwsModulithEventRegistrationMismatchException()
        }
        return try {
            eventClass.cast(event)
        } catch (_: ClassCastException) {
            throw AwsModulithEventRegistrationMismatchException()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> sanitizeExtractorFailure(block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        rethrowOrRegistrationMismatch(error)
    }

    @Suppress("ThrowsCount")
    private fun rethrowOrRegistrationMismatch(error: Throwable): Nothing {
        when (error) {
            is CancellationException -> throw error
            is Error -> throw error
            else -> throw AwsModulithEventRegistrationMismatchException()
        }
    }
}

private fun AwsModulithEventTypeRegistration<*>.snapshot(): AwsModulithEventTypeRegistration<*> =
    AwsModulithEventTypeRegistration(
        type = type,
        version = version,
        eventClass = eventClass,
        eventId = eventId,
        allowedHeaderNames = allowedHeaderNames.toSet(),
        headers = headers,
    )

private fun validateRegistration(registration: AwsModulithEventTypeRegistration<*>) {
    if (!EVENT_TYPE_PATTERN.matches(registration.type) || registration.version < 1) {
        throw AwsModulithConfigurationException()
    }
    if (registration.allowedHeaderNames.any { it.isBlank() }) {
        throw AwsModulithConfigurationException()
    }
}

private fun String.isValidAwsModulithEventId(): Boolean {
    if (isBlank() || length > MAX_EVENT_ID_LENGTH || toByteArray(StandardCharsets.UTF_8).size > MAX_EVENT_ID_BYTES) {
        return false
    }
    return none(Char::isISOControl)
}

private const val MAX_EVENT_ID_LENGTH = 128
private const val MAX_EVENT_ID_BYTES = 128
private val EVENT_TYPE_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,127}")
