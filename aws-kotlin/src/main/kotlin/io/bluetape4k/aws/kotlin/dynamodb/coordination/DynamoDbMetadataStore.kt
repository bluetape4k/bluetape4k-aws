@file:Suppress("TooManyFunctions")

package io.bluetape4k.aws.kotlin.dynamodb.coordination

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.deleteItem
import aws.sdk.kotlin.services.dynamodb.getItem
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.ReturnValuesOnConditionCheckFailure
import aws.sdk.kotlin.services.dynamodb.putItem
import aws.smithy.kotlin.runtime.SdkBaseException
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.error
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/** DynamoDB String metadata를 logical expiry와 bounded CAS로 저장하는 adapter입니다. */
class DynamoDbMetadataStore(
    private val client: DynamoDbClient,
    private val schema: DynamoDbCoordinationSchema,
    private val options: DynamoDbCoordinationOptions = DynamoDbCoordinationOptions(),
) : MetadataStore {

    companion object : KLoggingChannel()

    override suspend fun get(key: String): String? {
        val resolved = resolveKey(key)
        val response = executeSdk("get") {
            client.getItem {
                tableName = schema.tableName
                this.key = metadataKey(resolved.physicalKey)
                consistentRead = options.consistentRead
            }
        }
        val item = response.item ?: return null
        val parsed = parseAndCheckMetadataItem(item, resolved, coordinationNowEpochSeconds(options.clock))
        return if (parsed.expired) null else parsed.value
    }

    override suspend fun put(key: String, value: String, ttl: Duration?) {
        schema.validateMetadataKey(key)
        DynamoDbCoordinationSchema.validateMetadataValue(value)
        val expiresAt = ttl?.let {
            validateDynamoDbCoordinationDuration(it, "ttl")
            dynamoDbCoordinationExpiryEpochSeconds(coordinationNowEpochSeconds(options.clock), it)
        }
        val resolved = schema.resolve(DynamoDbCoordinationEntryKind.METADATA, key)
        executeSdk("put") {
            client.putItem {
                tableName = schema.tableName
                item = metadataItem(resolved.physicalKey, value, expiresAt)
            }
        }
    }

    override suspend fun putIfAbsent(key: String, value: String, ttl: Duration?): Boolean {
        schema.validateMetadataKey(key)
        DynamoDbCoordinationSchema.validateMetadataValue(value)
        val now = coordinationNowEpochSeconds(options.clock)
        val expiresAt = ttl?.let {
            validateDynamoDbCoordinationDuration(it, "ttl")
            dynamoDbCoordinationExpiryEpochSeconds(now, it)
        }
        val resolved = schema.resolve(DynamoDbCoordinationEntryKind.METADATA, key)
        val item = metadataItem(resolved.physicalKey, value, expiresAt)

        return try {
            executeSdk("putIfAbsent") {
                client.putItem {
                    tableName = schema.tableName
                    this.item = item
                    conditionExpression = DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
                    expressionAttributeNames = DynamoDbCoordinationExpressions.aliases(
                        schema,
                        setOf(DynamoDbCoordinationExpressions.PK_ALIAS),
                    )
                    returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld
                }
            }
            true
        } catch (error: ConditionalCheckFailedException) {
            val oldItem = requireOldItem(error, "putIfAbsent")
            val old = parseAndCheckMetadataItem(oldItem, resolved, now)
            if (!old.expired) {
                false
            } else {
                replaceExpired(
                    item = item,
                    old = old,
                    resolved = resolved,
                    now = now,
                )
            }
        }
    }

    override suspend fun remove(key: String): Boolean = removeInternal(key, expectedValue = null)

    override suspend fun removeIfValue(key: String, expectedValue: String): Boolean {
        schema.validateMetadataKey(key)
        DynamoDbCoordinationSchema.validateMetadataValue(expectedValue)
        return removeInternal(key, expectedValue)
    }

    private suspend fun removeInternal(key: String, expectedValue: String?): Boolean {
        schema.validateMetadataKey(key)
        val now = coordinationNowEpochSeconds(options.clock)
        val resolved = schema.resolve(DynamoDbCoordinationEntryKind.METADATA, key)

        return try {
            executeSdk("remove") {
                client.deleteItem {
                    tableName = schema.tableName
                    this.key = metadataKey(resolved.physicalKey)
                    conditionExpression = DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
                    expressionAttributeNames = DynamoDbCoordinationExpressions.aliases(
                        schema,
                        setOf(DynamoDbCoordinationExpressions.PK_ALIAS),
                    )
                    returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld
                }
            }
            false
        } catch (error: ConditionalCheckFailedException) {
            val oldItem = requireOldItem(error, "remove")
            val old = parseAndCheckMetadataItem(oldItem, resolved, now)
            if (expectedValue != null && old.value != expectedValue) {
                false
            } else {
                val removed = deleteObserved(resolved, old, now)
                removed && !old.expired
            }
        }
    }

    private suspend fun replaceExpired(
        item: Map<String, AttributeValue>,
        old: ParsedDynamoDbMetadataItem,
        resolved: ResolvedCoordinationKey,
        now: Long,
    ): Boolean = try {
        executeSdk("replaceExpired") {
            client.putItem {
                tableName = schema.tableName
                this.item = item
                conditionExpression = metadataObservedCondition(old, requireExpired = true)
                expressionAttributeNames = DynamoDbCoordinationExpressions.aliases(
                    schema,
                    setOf(
                        DynamoDbCoordinationExpressions.VALUE_ALIAS,
                        DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS,
                        DynamoDbCoordinationExpressions.TTL_ALIAS,
                    ),
                )
                expressionAttributeValues = metadataObservedValues(old, now, includeNow = true)
                returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld
            }
        }
        true
    } catch (error: ConditionalCheckFailedException) {
        error.item?.let { parseAndCheckMetadataItem(it, resolved, now) }
        false
    }

    private suspend fun deleteObserved(
        resolved: ResolvedCoordinationKey,
        old: ParsedDynamoDbMetadataItem,
        now: Long,
    ): Boolean = try {
        executeSdk("deleteObserved") {
            client.deleteItem {
                tableName = schema.tableName
                this.key = metadataKey(resolved.physicalKey)
                conditionExpression = metadataObservedCondition(old, requireExpired = old.expired)
                expressionAttributeNames = DynamoDbCoordinationExpressions.aliases(
                    schema,
                    setOf(
                        DynamoDbCoordinationExpressions.VALUE_ALIAS,
                        DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS,
                        DynamoDbCoordinationExpressions.TTL_ALIAS,
                    ),
                )
                expressionAttributeValues = metadataObservedValues(old, now, includeNow = old.expired)
                returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld
            }
        }
        true
    } catch (error: ConditionalCheckFailedException) {
        error.item?.let { parseAndCheckMetadataItem(it, resolved, now) }
        false
    }

    private fun metadataObservedCondition(
        old: ParsedDynamoDbMetadataItem,
        requireExpired: Boolean,
    ): String {
        val parts = mutableListOf(
            "${DynamoDbCoordinationExpressions.VALUE_ALIAS} = ${DynamoDbCoordinationExpressions.OBSERVED_VALUE}",
        )
        if (old.expiresAtEpochSeconds == null) {
            parts += "attribute_not_exists(${DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS})"
            parts += "attribute_not_exists(${DynamoDbCoordinationExpressions.TTL_ALIAS})"
        } else {
            parts += "${DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS} = " +
                DynamoDbCoordinationExpressions.OBSERVED_EXPIRES_AT_VALUE
            if (old.ttlEpochSeconds == null) {
                parts += "attribute_not_exists(${DynamoDbCoordinationExpressions.TTL_ALIAS})"
            } else {
                parts += "${DynamoDbCoordinationExpressions.TTL_ALIAS} = " +
                    DynamoDbCoordinationExpressions.OBSERVED_TTL
            }
            if (requireExpired) {
                parts += "${DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS} <= " +
                    DynamoDbCoordinationExpressions.NOW_VALUE
            }
        }
        return parts.joinToString(" AND ")
    }

    private fun metadataObservedValues(
        old: ParsedDynamoDbMetadataItem,
        now: Long,
        includeNow: Boolean,
    ): Map<String, AttributeValue> =
        buildMap {
            put(DynamoDbCoordinationExpressions.OBSERVED_VALUE, AttributeValue.S(old.value))
            old.expiresAtEpochSeconds?.let {
                put(DynamoDbCoordinationExpressions.OBSERVED_EXPIRES_AT_VALUE, AttributeValue.N(it.toString()))
                if (includeNow) {
                    put(DynamoDbCoordinationExpressions.NOW_VALUE, AttributeValue.N(now.toString()))
                }
            }
            old.ttlEpochSeconds?.let {
                put(DynamoDbCoordinationExpressions.OBSERVED_TTL, AttributeValue.N(it.toString()))
            }
        }

    private fun parseAndCheckMetadataItem(
        item: Map<String, AttributeValue>,
        resolved: ResolvedCoordinationKey,
        now: Long,
    ): ParsedDynamoDbMetadataItem {
        return try {
            val parsed = parseDynamoDbMetadataItem(schema, item, now)
            check(parsed.physicalKey == resolved.physicalKey) {
                "DynamoDB coordination metadata item key does not match the resolved schema key"
            }
            parsed
        } catch (error: IllegalStateException) {
            logMalformed("metadata-item", error)
            throw error
        }
    }

    private fun resolveKey(key: String): ResolvedCoordinationKey {
        schema.validateMetadataKey(key)
        return schema.resolve(DynamoDbCoordinationEntryKind.METADATA, key)
    }

    private fun metadataKey(physicalKey: String): Map<String, AttributeValue> =
        mapOf(schema.partitionKeyAttributeName to AttributeValue.S(physicalKey))

    private fun metadataItem(
        physicalKey: String,
        value: String,
        expiresAt: Long?,
    ): Map<String, AttributeValue> = buildMap {
        put(schema.partitionKeyAttributeName, AttributeValue.S(physicalKey))
        put(schema.valueAttributeName, AttributeValue.S(value))
        expiresAt?.let {
            put(schema.expiresAtAttributeName, AttributeValue.N(it.toString()))
            put(schema.ttlAttributeName, AttributeValue.N(it.toString()))
        }
    }

    private fun requireOldItem(
        error: ConditionalCheckFailedException,
        operation: String,
    ): Map<String, AttributeValue> = try {
        requireDynamoDbCoordinationOldItem(error.item, operation)
    } catch (failure: IllegalStateException) {
        logMalformed("$operation-old-item", failure)
        throw failure
    }

    private suspend fun <T> executeSdk(operation: String, block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ConditionalCheckFailedException) {
        throw error
    } catch (error: SdkBaseException) {
        log.error {
            "DynamoDB coordination SDK failure: operation=$operation table=${schema.tableName} " +
                "kind=METADATA namespace=${schema.namespace} error=${error::class.simpleName}"
        }
        throw error
    }

    private fun logMalformed(operation: String, error: IllegalStateException) {
        log.error {
            "DynamoDB coordination malformed response: operation=$operation table=${schema.tableName} " +
                "kind=METADATA namespace=${schema.namespace} error=${error::class.simpleName}"
        }
    }
}

private fun DynamoDbCoordinationSchema.validateMetadataKey(key: String) {
    key.validateCoordinationIdentifier("key")
}
