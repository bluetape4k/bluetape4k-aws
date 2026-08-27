package io.bluetape4k.aws.kotlin.dynamodb.coordination

import java.nio.charset.StandardCharsets
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** DynamoDB coordination item의 논리 종류입니다. */
enum class DynamoDbCoordinationEntryKind {
    /** owner와 fencing counter를 보관하는 lock item입니다. */
    LOCK,

    /** String payload와 선택적 만료를 보관하는 metadata item입니다. */
    METADATA,
}

/**
 * coordination logical key를 DynamoDB partition key로 변환하는 전략입니다.
 *
 * custom resolver는 `(namespace, kind, logicalKey)` 전체 입력 공간에서 결정적이고
 * injective해야 합니다. 이 속성은 임의의 함수에 대해 사후적으로 검증할 수 없으므로
 * resolver를 제공하는 caller의 계약이며, 충돌 결과는 정의되지 않습니다. library는
 * resolver 결과가 빈 값·제어 문자·DynamoDB key 크기 제한을 넘지 않는지만 확인합니다.
 */
fun interface DynamoDbCoordinationNameResolver {
    /** 주어진 coordination tuple의 물리 partition key를 반환합니다. */
    fun resolve(namespace: String, kind: DynamoDbCoordinationEntryKind, logicalKey: String): String

    companion object {
        /** delimiter가 logical key에 포함되어도 tuple 경계를 보존하는 기본 resolver입니다. */
        val DEFAULT: DynamoDbCoordinationNameResolver =
            DynamoDbCoordinationNameResolver { namespace, kind, logicalKey ->
                lengthPrefixed(namespace, kind.name, logicalKey)
            }

        private fun lengthPrefixed(vararg values: String): String = buildString {
            values.forEach { value ->
                val byteLength = value.toByteArray(StandardCharsets.UTF_8).size
                append(byteLength)
                append(':')
                append(value)
            }
        }
    }
}

/**
 * DynamoDB coordination이 사용할 기존 PK-only table과 attribute naming contract입니다.
 *
 * 이 schema는 table을 생성하거나 TTL을 활성화하지 않습니다. caller는
 * [partitionKeyAttributeName]과 동일한 String partition key를 가진 PK-only table을
 * 준비해야 하며, [ttlAttributeName] TTL은 metadata item에만 활성화해야 합니다.
 */
class DynamoDbCoordinationSchema(
    val tableName: String,
    val partitionKeyAttributeName: String = DEFAULT_PARTITION_KEY_ATTRIBUTE_NAME,
    val namespace: String = DEFAULT_NAMESPACE,
    val ownerAttributeName: String = DEFAULT_OWNER_ATTRIBUTE_NAME,
    val expiresAtAttributeName: String = DEFAULT_EXPIRES_AT_ATTRIBUTE_NAME,
    val fencingTokenAttributeName: String = DEFAULT_FENCING_TOKEN_ATTRIBUTE_NAME,
    val valueAttributeName: String = DEFAULT_VALUE_ATTRIBUTE_NAME,
    val ttlAttributeName: String = DEFAULT_TTL_ATTRIBUTE_NAME,
    val resolver: DynamoDbCoordinationNameResolver = DynamoDbCoordinationNameResolver.DEFAULT,
) {

    /** lock lease가 발급된 schema scope를 비교하기 위한 안정적인 canonical identity입니다. */
    internal val lockScopeId: String = canonicalize(
        "lock-scope-v1",
        tableName,
        partitionKeyAttributeName,
        namespace,
        ownerAttributeName,
        expiresAtAttributeName,
        fencingTokenAttributeName,
    )

    init {
        validateTableName(tableName)
        partitionKeyAttributeName.validateCoordinationIdentifier("partitionKeyAttributeName")
        namespace.validateCoordinationIdentifier("namespace")
        ownerAttributeName.validateCoordinationIdentifier("ownerAttributeName")
        expiresAtAttributeName.validateCoordinationIdentifier("expiresAtAttributeName")
        fencingTokenAttributeName.validateCoordinationIdentifier("fencingTokenAttributeName")
        valueAttributeName.validateCoordinationIdentifier("valueAttributeName")
        ttlAttributeName.validateCoordinationIdentifier("ttlAttributeName")

        val attributeNames = listOf(
            partitionKeyAttributeName,
            ownerAttributeName,
            expiresAtAttributeName,
            fencingTokenAttributeName,
            valueAttributeName,
            ttlAttributeName,
        )
        require(attributeNames.distinct().size == attributeNames.size) {
            "DynamoDB coordination attribute names must be distinct"
        }
    }

    /** 논리 key를 검증하고 resolver 결과와 lock scope를 함께 반환합니다. */
    internal fun resolve(
        kind: DynamoDbCoordinationEntryKind,
        logicalKey: String,
    ): ResolvedCoordinationKey {
        logicalKey.validateCoordinationIdentifier("logicalKey")
        val physicalKey = resolver.resolve(namespace, kind, logicalKey)
        physicalKey.validateCoordinationIdentifier(
            name = "resolved physical key",
            maxUtf8Bytes = MAX_RESOLVED_KEY_UTF8_BYTES,
        )
        return ResolvedCoordinationKey(logicalKey, physicalKey, lockScopeId)
    }

    companion object {
        private const val MIN_TABLE_NAME_LENGTH = 3
        private const val MAX_TABLE_NAME_LENGTH = 255
        private const val DEFAULT_PARTITION_KEY_ATTRIBUTE_NAME = "id"
        private const val DEFAULT_NAMESPACE = "default"
        private const val DEFAULT_OWNER_ATTRIBUTE_NAME = "ownerId"
        private const val DEFAULT_EXPIRES_AT_ATTRIBUTE_NAME = "expiresAt"
        private const val DEFAULT_FENCING_TOKEN_ATTRIBUTE_NAME = "fencingToken"
        private const val DEFAULT_VALUE_ATTRIBUTE_NAME = "value"
        private const val DEFAULT_TTL_ATTRIBUTE_NAME = "ttlEpochSeconds"

        /** logical key·namespace·owner·attribute name에 적용하는 UTF-8 상한입니다. */
        const val MAX_IDENTIFIER_UTF8_BYTES: Int = 256

        /** metadata item value가 request/item overhead를 남기도록 적용하는 상한입니다. */
        const val MAX_METADATA_VALUE_UTF8_BYTES: Int = 350_000

        /** DynamoDB String partition key에 적용하는 UTF-8 상한입니다. */
        const val MAX_RESOLVED_KEY_UTF8_BYTES: Int = 2_048

        /** lock lease scope identity에 적용하는 보수적인 UTF-8 상한입니다. */
        internal const val MAX_SCOPE_ID_UTF8_BYTES: Int = 4_096

        /** coordination lease와 metadata TTL에 허용하는 최대 기간입니다. */
        val MAX_COORDINATION_DURATION: Duration = 365.days

        /** DynamoDB table-name 문자와 길이 규칙을 확인합니다. */
        internal fun validateTableName(tableName: String): String {
            require(
                tableName.length in MIN_TABLE_NAME_LENGTH..MAX_TABLE_NAME_LENGTH &&
                        TABLE_NAME_PATTERN.matches(tableName),
            ) {
                "tableName must contain $MIN_TABLE_NAME_LENGTH..$MAX_TABLE_NAME_LENGTH ASCII letters, " +
                        "digits, '_', '-' or '.', but was invalid"
            }
            return tableName
        }

        /** metadata payload의 UTF-8 크기를 확인합니다. */
        internal fun validateMetadataValue(value: String): String {
            require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_METADATA_VALUE_UTF8_BYTES) {
                "metadata value UTF-8 length must be <= $MAX_METADATA_VALUE_UTF8_BYTES bytes"
            }
            return value
        }

        private val TABLE_NAME_PATTERN = Regex("[A-Za-z0-9_.-]{$MIN_TABLE_NAME_LENGTH,$MAX_TABLE_NAME_LENGTH}")
    }
}

/** adapter가 resolver 결과와 schema scope를 함께 전달하는 내부 값입니다. */
internal data class ResolvedCoordinationKey(
    val logicalKey: String,
    val physicalKey: String,
    val scopeId: String,
)

/** options와 adapter가 공유하는 bounded coordination duration 검증입니다. */
internal fun validateDynamoDbCoordinationDuration(
    duration: Duration,
    name: String = "duration",
): Duration {
    require(duration.isFinite()) { "$name must be finite, but was $duration" }
    require(duration > Duration.ZERO) { "$name must be positive, but was $duration" }
    require(duration.inWholeSeconds.seconds == duration) {
        "$name must use whole seconds, but was $duration"
    }
    require(duration <= DynamoDbCoordinationSchema.MAX_COORDINATION_DURATION) {
        "$name must be <= ${DynamoDbCoordinationSchema.MAX_COORDINATION_DURATION}, but was $duration"
    }
    return duration
}

/** current epoch second와 duration을 overflow 없이 더합니다. */
internal fun dynamoDbCoordinationExpiryEpochSeconds(nowEpochSeconds: Long, duration: Duration): Long {
    validateDynamoDbCoordinationDuration(duration, "leaseDuration")
    require(nowEpochSeconds >= 0) {
        "coordination now epoch second must be non-negative, but was $nowEpochSeconds"
    }
    val delta = duration.inWholeSeconds
    require(nowEpochSeconds <= Long.MAX_VALUE - delta) {
        "coordination expiry epoch second overflows Long"
    }
    return nowEpochSeconds + delta
}

/** coordination identifier에 공통으로 적용하는 blank/control/UTF-8 검증입니다. */
internal fun String.validateCoordinationIdentifier(
    name: String,
    maxUtf8Bytes: Int = DynamoDbCoordinationSchema.MAX_IDENTIFIER_UTF8_BYTES,
): String {
    require(isNotBlank()) { "$name must not be blank" }
    require(none(Char::isISOControl)) { "$name must not contain control characters" }
    require(toByteArray(StandardCharsets.UTF_8).size <= maxUtf8Bytes) {
        "$name UTF-8 length must be <= $maxUtf8Bytes bytes"
    }
    return this
}

/** length-prefixed UTF-8 tuple canonicalization으로 scope identity를 생성합니다. */
private fun canonicalize(vararg values: String): String = buildString {
    values.forEach { value ->
        val byteLength = value.toByteArray(StandardCharsets.UTF_8).size
        append(byteLength)
        append(':')
        append(value)
    }
}.also {
    require(it.toByteArray(StandardCharsets.UTF_8).size <= DynamoDbCoordinationSchema.MAX_SCOPE_ID_UTF8_BYTES) {
        "coordination scope id exceeds ${DynamoDbCoordinationSchema.MAX_SCOPE_ID_UTF8_BYTES} bytes"
    }
}
