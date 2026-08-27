package io.bluetape4k.aws.spring.modulith

import java.io.Serializable
import java.util.Collections
import java.util.LinkedHashMap

/**
 * AWS 외부화 transport가 사용하는 versioned 이벤트 envelope입니다.
 *
 * [payload]는 [org.springframework.modulith.events.core.EventSerializer]가 반환한
 * 문자열을 다시 해석하지 않고 그대로 담습니다. business header는 registry allowlist를
 * 거친 뒤에만 wire에 포함됩니다.
 */
@ConsistentCopyVisibility
data class AwsModulithEventEnvelope private constructor(
    val specVersion: Int = 1,
    val id: String,
    val type: String,
    val version: Int,
    val payload: String,
    private val headerSnapshot: ImmutableStringMap,
) : Serializable {
    constructor(
        specVersion: Int = 1,
        id: String,
        type: String,
        version: Int,
        payload: String,
        headers: Map<String, String> = emptyMap(),
    ) : this(specVersion, id, type, version, payload, ImmutableStringMap(headers))

    val headers: Map<String, String>
        get() = headerSnapshot.values

    override fun toString(): String =
        "AwsModulithEventEnvelope(specVersion=$specVersion, type=$type, version=$version, payload=[redacted], headers=[redacted])"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** envelope body와 AWS String message attributes의 immutable transport snapshot입니다. */
@ConsistentCopyVisibility
internal data class AwsModulithEncodedEvent private constructor(
    val body: String,
    private val attributeSnapshot: ImmutableStringMap,
) : Serializable {
    constructor(body: String, messageAttributes: Map<String, String>) :
        this(body, ImmutableStringMap(messageAttributes))

    val messageAttributes: Map<String, String>
        get() = attributeSnapshot.values

    override fun toString(): String =
        "AwsModulithEncodedEvent(body=[redacted], messageAttributes=[redacted])"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private class ImmutableStringMap(source: Map<String, String>) : Serializable {
    val values: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(source))

    override fun equals(other: Any?): Boolean = other is ImmutableStringMap && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "[redacted]"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
