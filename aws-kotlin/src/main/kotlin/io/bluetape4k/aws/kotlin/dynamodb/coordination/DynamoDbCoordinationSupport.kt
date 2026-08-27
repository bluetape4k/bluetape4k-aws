@file:Suppress("TooManyFunctions")

package io.bluetape4k.aws.kotlin.dynamodb.coordination

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import java.time.Clock
import java.time.Instant

/** DynamoDB coordination adapter가 공유하는 고정 expression과 attribute alias입니다. */
internal object DynamoDbCoordinationExpressions {
    const val PK_ALIAS = "#pk"
    const val OWNER_ALIAS = "#owner"
    const val EXPIRES_AT_ALIAS = "#expiresAt"
    const val FENCING_TOKEN_ALIAS = "#fencingToken"
    const val VALUE_ALIAS = "#value"
    const val TTL_ALIAS = "#ttl"

    const val OWNER_VALUE = ":owner"
    const val EXPIRES_AT_VALUE = ":expiresAt"
    const val NOW_VALUE = ":now"
    const val TOKEN_VALUE = ":token"
    const val PREVIOUS_EXPIRES_AT_VALUE = ":previousExpiresAt"
    const val OBSERVED_EXPIRES_AT_VALUE = ":observedExpiresAt"
    const val OBSERVED_TOKEN_VALUE = ":observedToken"
    const val OBSERVED_OWNER_VALUE = ":observedOwner"
    const val VALUE_VALUE = ":value"
    const val OBSERVED_VALUE = ":observedValue"
    const val TTL_VALUE = ":ttl"
    const val OBSERVED_TTL = ":observedTtl"
    const val MAX_TOKEN_VALUE = ":maxToken"
    const val ZERO_VALUE = ":zero"
    const val ONE_VALUE = ":one"

    const val LOCK_ACQUIRE_UPDATE =
        "SET #owner = :owner, #expiresAt = :expiresAt, " +
            "#fencingToken = if_not_exists(#fencingToken, :zero) + :one"
    const val LOCK_KEY_ABSENT_CONDITION = "attribute_not_exists(#pk)"
    const val LOCK_RENEW_UPDATE = "SET #expiresAt = :expiresAt"
    const val LOCK_RELEASE_UPDATE = "SET #expiresAt = :now REMOVE #owner"

    fun aliases(
        schema: DynamoDbCoordinationSchema,
        usedAliases: Set<String> = setOf(
            PK_ALIAS,
            OWNER_ALIAS,
            EXPIRES_AT_ALIAS,
            FENCING_TOKEN_ALIAS,
            VALUE_ALIAS,
            TTL_ALIAS,
        ),
    ): Map<String, String> = mapOf(
        PK_ALIAS to schema.partitionKeyAttributeName,
        OWNER_ALIAS to schema.ownerAttributeName,
        EXPIRES_AT_ALIAS to schema.expiresAtAttributeName,
        FENCING_TOKEN_ALIAS to schema.fencingTokenAttributeName,
        VALUE_ALIAS to schema.valueAttributeName,
        TTL_ALIAS to schema.ttlAttributeName,
    ).filterKeys { it in usedAliases }
}

/** lock item을 해석한 결과입니다. */
internal data class ParsedDynamoDbLockItem(
    val physicalKey: String,
    val ownerId: String?,
    val expiresAtEpochSeconds: Long,
    val fencingToken: Long,
    val expired: Boolean,
) {
    val fencingTokenExhausted: Boolean get() = fencingToken == Long.MAX_VALUE
}

/** metadata item을 해석한 결과입니다. */
internal data class ParsedDynamoDbMetadataItem(
    val physicalKey: String,
    val value: String,
    val expiresAtEpochSeconds: Long?,
    val ttlEpochSeconds: Long?,
    val expired: Boolean,
)

/** 고정 clock의 epoch second를 반환합니다. */
internal fun coordinationNowEpochSeconds(clock: Clock): Long = Instant.now(clock).epochSecond.also {
    require(it >= 0) {
        "coordination now epoch second must be non-negative, but was $it"
    }
}

/** AWS SDK 응답/조건 실패가 반환한 item을 필수 값으로 승격합니다. */
internal fun requireDynamoDbCoordinationOldItem(
    item: Map<String, AttributeValue>?,
    operation: String,
): Map<String, AttributeValue> = item ?: throw IllegalStateException(
    "DynamoDB coordination $operation did not return AllOld item on conditional failure",
)

/** lock item의 persisted schema를 fail-closed 방식으로 확인합니다. */
internal fun parseDynamoDbLockItem(
    schema: DynamoDbCoordinationSchema,
    item: Map<String, AttributeValue>,
    nowEpochSeconds: Long,
): ParsedDynamoDbLockItem {
    val physicalKey = item.requiredString(
        schema.partitionKeyAttributeName,
        "partition key",
        DynamoDbCoordinationSchema.MAX_RESOLVED_KEY_UTF8_BYTES,
    )
    val owner = item.optionalString(schema.ownerAttributeName, "ownerId")
    val expiresAt = item.requiredCanonicalLong(schema.expiresAtAttributeName, "expiresAt", nonNegative = true)
    val token = item.requiredCanonicalLong(schema.fencingTokenAttributeName, "fencingToken", nonNegative = false)
    checkMalformed(token > 0) { "fencingToken must be positive" }
    if (owner == null && expiresAt > nowEpochSeconds) {
        throw IllegalStateException("malformed DynamoDB lock item: future item has no owner")
    }
    return ParsedDynamoDbLockItem(
        physicalKey = physicalKey,
        ownerId = owner,
        expiresAtEpochSeconds = expiresAt,
        fencingToken = token,
        expired = expiresAt <= nowEpochSeconds,
    )
}

/** metadata item의 String payload와 optional logical expiry를 확인합니다. */
internal fun parseDynamoDbMetadataItem(
    schema: DynamoDbCoordinationSchema,
    item: Map<String, AttributeValue>,
    nowEpochSeconds: Long,
): ParsedDynamoDbMetadataItem {
    val physicalKey = item.requiredString(
        schema.partitionKeyAttributeName,
        "partition key",
        DynamoDbCoordinationSchema.MAX_RESOLVED_KEY_UTF8_BYTES,
    )
    val value = item.requiredMetadataValue(schema.valueAttributeName)

    val expires = item.optionalCanonicalLong(schema.expiresAtAttributeName, "expiresAt")
    val ttl = item.optionalCanonicalLong(schema.ttlAttributeName, "ttlEpochSeconds")
    checkMalformed(expires != null || ttl == null) { "ttlEpochSeconds requires expiresAt" }
    if (expires != null && ttl != null) {
        checkMalformed(expires == ttl) { "expiresAt and ttlEpochSeconds differ" }
    }
    return ParsedDynamoDbMetadataItem(
        physicalKey = physicalKey,
        value = value,
        expiresAtEpochSeconds = expires,
        ttlEpochSeconds = ttl,
        expired = expires != null && expires <= nowEpochSeconds,
    )
}

/** lease가 현재 adapter schema에 속하는지 확인하고 물리 key를 한 번만 계산합니다. */
internal fun DynamoDbCoordinationSchema.requireLeaseScope(lease: LockLease): ResolvedCoordinationKey {
    val resolved = resolve(DynamoDbCoordinationEntryKind.LOCK, lease.key)
    require(lease.tableName == tableName) { "LockLease tableName does not match schema" }
    require(lease.partitionKeyAttributeName == partitionKeyAttributeName) {
        "LockLease partitionKeyAttributeName does not match schema"
    }
    require(lease.namespace == namespace) { "LockLease namespace does not match schema" }
    require(lease.physicalKey == resolved.physicalKey) { "LockLease physical key does not match schema" }
    require(lease.scopeId == resolved.scopeId) { "LockLease scopeId does not match schema" }
    return resolved
}

private fun Map<String, AttributeValue>.requiredString(
    attributeName: String,
    label: String,
    maxUtf8Bytes: Int = DynamoDbCoordinationSchema.MAX_IDENTIFIER_UTF8_BYTES,
): String {
    val value = this[attributeName] as? AttributeValue.S
        ?: throw IllegalStateException("malformed DynamoDB coordination item: $label must be String")
    return persistedIdentifier(value.value, label, maxUtf8Bytes)
}

private fun Map<String, AttributeValue>.optionalString(attributeName: String, label: String): String? {
    val value = this[attributeName] ?: return null
    val stringValue = value as? AttributeValue.S
        ?: throw IllegalStateException("malformed DynamoDB coordination item: $label must be String")
    return persistedIdentifier(stringValue.value, label)
}

private fun Map<String, AttributeValue>.requiredMetadataValue(attributeName: String): String {
    val value = this[attributeName] as? AttributeValue.S
        ?: throw IllegalStateException("malformed DynamoDB coordination item: value must be String")
    return try {
        DynamoDbCoordinationSchema.validateMetadataValue(value.value)
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("malformed DynamoDB coordination item: value is too large", error)
    }
}

private fun Map<String, AttributeValue>.requiredCanonicalLong(
    attributeName: String,
    label: String,
    nonNegative: Boolean,
): Long {
    val value = this[attributeName] as? AttributeValue.N
        ?: throw IllegalStateException("malformed DynamoDB coordination item: $label must be Number")
    return parseCanonicalLong(value.value, label, nonNegative)
}

private fun Map<String, AttributeValue>.optionalCanonicalLong(attributeName: String, label: String): Long? {
    val value = this[attributeName] ?: return null
    val numberValue = value as? AttributeValue.N
        ?: throw IllegalStateException("malformed DynamoDB coordination item: $label must be Number")
    return parseCanonicalLong(numberValue.value, label, nonNegative = true)
}

private fun parseCanonicalLong(value: String, label: String, nonNegative: Boolean): Long {
    checkMalformed(value == "0" || value.matches(Regex("[1-9][0-9]*"))) {
        "$label must be a canonical integer"
    }
    val parsed = value.toLongOrNull()
        ?: throw IllegalStateException("malformed DynamoDB coordination item: $label overflows Long")
    if (nonNegative) {
        checkMalformed(parsed >= 0) { "$label must be non-negative" }
    }
    return parsed
}

private inline fun checkMalformed(condition: Boolean, message: () -> String) {
    if (!condition) {
        throw IllegalStateException("malformed DynamoDB coordination item: ${message()}")
    }
}

private fun persistedIdentifier(
    value: String,
    label: String,
    maxUtf8Bytes: Int = DynamoDbCoordinationSchema.MAX_IDENTIFIER_UTF8_BYTES,
): String = try {
    value.validateCoordinationIdentifier(label, maxUtf8Bytes)
} catch (error: IllegalArgumentException) {
    throw IllegalStateException("malformed DynamoDB coordination item: $label is invalid", error)
}
