@file:Suppress("TooManyFunctions", "LongMethod")

package io.bluetape4k.aws.kotlin.dynamodb.coordination

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.ReturnValue
import aws.sdk.kotlin.services.dynamodb.model.ReturnValuesOnConditionCheckFailure
import aws.sdk.kotlin.services.dynamodb.putItem
import aws.sdk.kotlin.services.dynamodb.updateItem
import aws.smithy.kotlin.runtime.SdkBaseException
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.error
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/**
 * DynamoDB conditional write만으로 fencing token을 발급하는 분산 lock adapter입니다.
 *
 * client의 lifecycle은 호출자가 소유합니다. 각 연산은 pre-read나 내부 retry 없이
 * 빠른 경로에서 한 번, 만료 takeover 경로에서 최대 두 번의 mutation만 수행합니다.
 */
class DynamoDbDistributedLock(
    private val client: DynamoDbClient,
    private val schema: DynamoDbCoordinationSchema,
    private val options: DynamoDbCoordinationOptions = DynamoDbCoordinationOptions(),
) : DistributedLock {

    companion object : KLoggingChannel() {
        private const val MAX_USABLE_FENCING_TOKEN: Long = Long.MAX_VALUE - 1
    }

    /** 기본 lease duration을 사용해 lock을 획득합니다. */
    suspend fun tryAcquire(key: String, ownerId: String): LockLease? =
        tryAcquire(key, ownerId, options.defaultLeaseDuration)

    /** 기본 lease duration을 사용해 lease를 갱신합니다. */
    suspend fun renew(lease: LockLease): LockLease? = renew(lease, options.defaultLeaseDuration)

    /** [renew]와 동일한 조건부 연장 동작을 기본 duration으로 수행합니다. */
    suspend fun heartbeat(lease: LockLease): LockLease? = heartbeat(lease, options.defaultLeaseDuration)

    override suspend fun tryAcquire(
        key: String,
        ownerId: String,
        leaseDuration: Duration,
    ): LockLease? {
        key.validateCoordinationIdentifier("key")
        ownerId.validateCoordinationIdentifier("ownerId")
        validateDynamoDbCoordinationDuration(leaseDuration, "leaseDuration")

        val now = coordinationNowEpochSeconds(options.clock)
        val expiresAt = dynamoDbCoordinationExpiryEpochSeconds(now, leaseDuration)
        val resolved = schema.resolve(DynamoDbCoordinationEntryKind.LOCK, key)
        val keyMap = lockKey(resolved.physicalKey)

        return try {
            val response = executeSdk("acquire") {
                client.updateItem {
                    tableName = schema.tableName
                    this.key = keyMap
                    updateExpression = DynamoDbCoordinationExpressions.LOCK_ACQUIRE_UPDATE
                    conditionExpression = DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
                    expressionAttributeNames = DynamoDbCoordinationExpressions.aliases(
                        schema,
                        setOf(
                            DynamoDbCoordinationExpressions.PK_ALIAS,
                            DynamoDbCoordinationExpressions.OWNER_ALIAS,
                            DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS,
                            DynamoDbCoordinationExpressions.FENCING_TOKEN_ALIAS,
                        ),
                    )
                    expressionAttributeValues = mapOf(
                        DynamoDbCoordinationExpressions.OWNER_VALUE to AttributeValue.S(ownerId),
                        DynamoDbCoordinationExpressions.EXPIRES_AT_VALUE to number(expiresAt),
                        DynamoDbCoordinationExpressions.ZERO_VALUE to number(0L),
                        DynamoDbCoordinationExpressions.ONE_VALUE to number(1L),
                    )
                    returnValues = ReturnValue.AllNew
                    returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld
                }
            }
            leaseFromAllNew(
                item = response.attributes,
                resolved = resolved,
                key = key,
                ownerId = ownerId,
                expectedExpiresAt = expiresAt,
                expectedFencingToken = 1L,
                now = now,
            )
        } catch (error: ConditionalCheckFailedException) {
            val oldItem = requireOldItem(error, "acquire")
            val old = parseAndCheckLockItem(oldItem, resolved, now)
            if (!old.expired) {
                null
            } else if (old.fencingTokenExhausted || old.fencingToken >= MAX_USABLE_FENCING_TOKEN) {
                throw IllegalStateException("fencing token exhausted")
            } else {
                takeover(
                    resolved = resolved,
                    key = key,
                    ownerId = ownerId,
                    expiresAt = expiresAt,
                    now = now,
                    old = old,
                )
            }
        }
    }

    override suspend fun renew(lease: LockLease, leaseDuration: Duration): LockLease? =
        renewInternal(lease, leaseDuration)

    override suspend fun heartbeat(lease: LockLease, leaseDuration: Duration): LockLease? =
        renewInternal(lease, leaseDuration)

    override suspend fun release(lease: LockLease): Boolean {
        val resolved = schema.requireLeaseScope(lease)
        val now = coordinationNowEpochSeconds(options.clock)
        val keyMap = lockKey(resolved.physicalKey)

        return try {
            val response = executeSdk("release") {
                client.updateItem {
                    tableName = schema.tableName
                    key = keyMap
                    updateExpression = DynamoDbCoordinationExpressions.LOCK_RELEASE_UPDATE
                    conditionExpression = lockLeaseCondition()
                    expressionAttributeNames = DynamoDbCoordinationExpressions.aliases(
                        schema,
                        setOf(
                            DynamoDbCoordinationExpressions.OWNER_ALIAS,
                            DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS,
                            DynamoDbCoordinationExpressions.FENCING_TOKEN_ALIAS,
                        ),
                    )
                    expressionAttributeValues = lockReleaseValues(lease, now)
                    returnValues = ReturnValue.AllOld
                    returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld
                }
            }
            val oldItem = response.attributes ?: run {
                val failure = IllegalStateException("DynamoDB coordination release did not return AllOld item")
                logMalformed("release-all-old", failure)
                throw failure
            }
            val old = parseAndCheckLockItem(oldItem, resolved, now)
            check(old.ownerId == lease.ownerId && old.fencingToken == lease.fencingToken) {
                "DynamoDB coordination release returned an item that does not match the lease"
            }
            check(old.expiresAtEpochSeconds == lease.expiresAtEpochSeconds && !old.expired) {
                "DynamoDB coordination release returned a stale item"
            }
            true
        } catch (error: ConditionalCheckFailedException) {
            val oldItem = error.item
            if (oldItem != null) {
                parseAndCheckLockItem(oldItem, resolved, now)
            }
            false
        }
    }

    private suspend fun renewInternal(lease: LockLease, leaseDuration: Duration): LockLease? {
        validateDynamoDbCoordinationDuration(leaseDuration, "leaseDuration")
        val resolved = schema.requireLeaseScope(lease)
        val now = coordinationNowEpochSeconds(options.clock)
        val expiresAt = dynamoDbCoordinationExpiryEpochSeconds(now, leaseDuration)
        val keyMap = lockKey(resolved.physicalKey)

        return try {
            val response = executeSdk("renew") {
                client.updateItem {
                    tableName = schema.tableName
                    key = keyMap
                    updateExpression = DynamoDbCoordinationExpressions.LOCK_RENEW_UPDATE
                    conditionExpression = lockLeaseCondition()
                    expressionAttributeNames = DynamoDbCoordinationExpressions.aliases(
                        schema,
                        setOf(
                            DynamoDbCoordinationExpressions.OWNER_ALIAS,
                            DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS,
                            DynamoDbCoordinationExpressions.FENCING_TOKEN_ALIAS,
                        ),
                    )
                    expressionAttributeValues = lockLeaseValues(lease, expiresAt, now)
                    returnValues = ReturnValue.AllNew
                    returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld
                }
            }
            leaseFromAllNew(
                item = response.attributes,
                resolved = resolved,
                key = lease.key,
                ownerId = lease.ownerId,
                expectedExpiresAt = expiresAt,
                expectedFencingToken = lease.fencingToken,
                now = now,
            )
        } catch (error: ConditionalCheckFailedException) {
            val oldItem = error.item
            if (oldItem != null) {
                parseAndCheckLockItem(oldItem, resolved, now)
            }
            null
        }
    }

    private suspend fun takeover(
        resolved: ResolvedCoordinationKey,
        key: String,
        ownerId: String,
        expiresAt: Long,
        now: Long,
        old: ParsedDynamoDbLockItem,
    ): LockLease? {
        val ownerCondition = if (old.ownerId == null) {
            "attribute_not_exists(${DynamoDbCoordinationExpressions.OWNER_ALIAS})"
        } else {
            "${DynamoDbCoordinationExpressions.OWNER_ALIAS} = ${DynamoDbCoordinationExpressions.OBSERVED_OWNER_VALUE}"
        }
        val condition = listOf(
            ownerCondition,
            "${DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS} = " +
                DynamoDbCoordinationExpressions.OBSERVED_EXPIRES_AT_VALUE,
            "${DynamoDbCoordinationExpressions.FENCING_TOKEN_ALIAS} = " +
                DynamoDbCoordinationExpressions.OBSERVED_TOKEN_VALUE,
            "${DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS} <= " +
                DynamoDbCoordinationExpressions.NOW_VALUE,
            "${DynamoDbCoordinationExpressions.FENCING_TOKEN_ALIAS} < " +
                DynamoDbCoordinationExpressions.MAX_TOKEN_VALUE,
        ).joinToString(" AND ")
        val values = buildMap<String, AttributeValue> {
            put(DynamoDbCoordinationExpressions.OWNER_VALUE, AttributeValue.S(ownerId))
            put(DynamoDbCoordinationExpressions.EXPIRES_AT_VALUE, number(expiresAt))
            put(DynamoDbCoordinationExpressions.ZERO_VALUE, number(0L))
            put(DynamoDbCoordinationExpressions.ONE_VALUE, number(1L))
            put(DynamoDbCoordinationExpressions.OBSERVED_EXPIRES_AT_VALUE, number(old.expiresAtEpochSeconds))
            put(DynamoDbCoordinationExpressions.OBSERVED_TOKEN_VALUE, number(old.fencingToken))
            put(DynamoDbCoordinationExpressions.NOW_VALUE, number(now))
            old.ownerId?.let { put(DynamoDbCoordinationExpressions.OBSERVED_OWNER_VALUE, AttributeValue.S(it)) }
            put(DynamoDbCoordinationExpressions.MAX_TOKEN_VALUE, number(Long.MAX_VALUE))
        }
        return try {
            val response = executeSdk("takeover") {
                client.updateItem {
                    tableName = schema.tableName
                    this.key = lockKey(resolved.physicalKey)
                    updateExpression = DynamoDbCoordinationExpressions.LOCK_ACQUIRE_UPDATE
                    conditionExpression = condition
                    expressionAttributeNames = DynamoDbCoordinationExpressions.aliases(
                        schema,
                        setOf(
                            DynamoDbCoordinationExpressions.OWNER_ALIAS,
                            DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS,
                            DynamoDbCoordinationExpressions.FENCING_TOKEN_ALIAS,
                        ),
                    )
                    expressionAttributeValues = values
                    returnValues = ReturnValue.AllNew
                    returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld
                }
            }
            leaseFromAllNew(
                item = response.attributes,
                resolved = resolved,
                key = key,
                ownerId = ownerId,
                expectedExpiresAt = expiresAt,
                expectedFencingToken = old.fencingToken + 1,
                now = now,
            )
        } catch (error: ConditionalCheckFailedException) {
            error.item?.let { parseAndCheckLockItem(it, resolved, now) }
            null
        }
    }

    private fun lockLeaseCondition(): String = listOf(
        "${DynamoDbCoordinationExpressions.OWNER_ALIAS} = ${DynamoDbCoordinationExpressions.OWNER_VALUE}",
        "${DynamoDbCoordinationExpressions.FENCING_TOKEN_ALIAS} = ${DynamoDbCoordinationExpressions.TOKEN_VALUE}",
        "${DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS} = " +
            DynamoDbCoordinationExpressions.PREVIOUS_EXPIRES_AT_VALUE,
        "${DynamoDbCoordinationExpressions.EXPIRES_AT_ALIAS} > ${DynamoDbCoordinationExpressions.NOW_VALUE}",
    ).joinToString(" AND ")

    private fun lockLeaseValues(lease: LockLease, expiresAt: Long, now: Long): Map<String, AttributeValue> = mapOf(
        DynamoDbCoordinationExpressions.OWNER_VALUE to AttributeValue.S(lease.ownerId),
        DynamoDbCoordinationExpressions.TOKEN_VALUE to number(lease.fencingToken),
        DynamoDbCoordinationExpressions.PREVIOUS_EXPIRES_AT_VALUE to number(lease.expiresAtEpochSeconds),
        DynamoDbCoordinationExpressions.EXPIRES_AT_VALUE to number(expiresAt),
        DynamoDbCoordinationExpressions.NOW_VALUE to number(now),
    )

    private fun lockReleaseValues(lease: LockLease, now: Long): Map<String, AttributeValue> = mapOf(
        DynamoDbCoordinationExpressions.OWNER_VALUE to AttributeValue.S(lease.ownerId),
        DynamoDbCoordinationExpressions.TOKEN_VALUE to number(lease.fencingToken),
        DynamoDbCoordinationExpressions.PREVIOUS_EXPIRES_AT_VALUE to number(lease.expiresAtEpochSeconds),
        DynamoDbCoordinationExpressions.NOW_VALUE to number(now),
    )

    private fun parseAndCheckLockItem(
        item: Map<String, AttributeValue>,
        resolved: ResolvedCoordinationKey,
        now: Long,
    ): ParsedDynamoDbLockItem = try {
        val parsed = parseDynamoDbLockItem(schema, item, now)
        check(parsed.physicalKey == resolved.physicalKey) {
            "DynamoDB coordination lock item key does not match the resolved schema key"
        }
        parsed
    } catch (error: IllegalStateException) {
        logMalformed("lock-item", error)
        throw error
    }

    private fun leaseFromAllNew(
        item: Map<String, AttributeValue>?,
        resolved: ResolvedCoordinationKey,
        key: String,
        ownerId: String,
        expectedExpiresAt: Long,
        expectedFencingToken: Long,
        now: Long,
    ): LockLease {
        val allNewItem = item ?: run {
            val failure = IllegalStateException("DynamoDB coordination lock did not return AllNew item")
            logMalformed("all-new", failure)
            throw failure
        }
        val parsed = parseAndCheckLockItem(
            allNewItem,
            resolved,
            now,
        )
        check(parsed.ownerId == ownerId) { "DynamoDB coordination lock returned a different owner" }
        check(parsed.expiresAtEpochSeconds == expectedExpiresAt) {
            "DynamoDB coordination lock returned an unexpected expiry"
        }
        check(parsed.fencingToken == expectedFencingToken) {
            "DynamoDB coordination lock returned an unexpected fencing token"
        }
        check(!parsed.fencingTokenExhausted) { "fencing token exhausted" }
        return LockLease(
            key = key,
            ownerId = ownerId,
            fencingToken = parsed.fencingToken,
            expiresAtEpochSeconds = parsed.expiresAtEpochSeconds,
            tableName = schema.tableName,
            partitionKeyAttributeName = schema.partitionKeyAttributeName,
            namespace = schema.namespace,
            physicalKey = resolved.physicalKey,
            scopeId = resolved.scopeId,
        )
    }

    private fun lockKey(physicalKey: String): Map<String, AttributeValue> =
        mapOf(schema.partitionKeyAttributeName to AttributeValue.S(physicalKey))

    private fun number(value: Long): AttributeValue = AttributeValue.N(value.toString())

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
                "kind=LOCK namespace=${schema.namespace} error=${error::class.simpleName}"
        }
        throw error
    }

    private fun logMalformed(operation: String, error: IllegalStateException) {
        log.error {
            "DynamoDB coordination malformed response: operation=$operation table=${schema.tableName} " +
                "kind=LOCK namespace=${schema.namespace} error=${error::class.simpleName}"
        }
    }
}
