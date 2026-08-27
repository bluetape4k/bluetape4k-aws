package io.bluetape4k.aws.kotlin.dynamodb.coordination

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class DynamoDbCoordinationSupportTest {

    private val schema = DynamoDbCoordinationSchema(tableName = "coordination", namespace = "orders")

    @Test
    fun `malformed lock item의 fractional number와 missing field는 fail closed한다`() {
        val fractional = mapOf(
            "id" to AttributeValue.S("lock-key"),
            "ownerId" to AttributeValue.S("worker-1"),
            "expiresAt" to AttributeValue.N("100.0"),
            "fencingToken" to AttributeValue.N("1"),
        )
        val missingToken = mapOf(
            "id" to AttributeValue.S("lock-key"),
            "ownerId" to AttributeValue.S("worker-1"),
            "expiresAt" to AttributeValue.N("100"),
        )

        assertFailsWith<IllegalStateException> {
            parseDynamoDbLockItem(schema, fractional, nowEpochSeconds = 100)
        }
        assertFailsWith<IllegalStateException> {
            parseDynamoDbLockItem(schema, missingToken, nowEpochSeconds = 100)
        }

        parseDynamoDbLockItem(
            schema,
            mapOf(
                "id" to AttributeValue.S("lock-key"),
                "expiresAt" to AttributeValue.N("100"),
                "fencingToken" to AttributeValue.N(Long.MAX_VALUE.toString()),
            ),
            nowEpochSeconds = 100,
        ).fencingTokenExhausted.shouldBeTrue()
    }

    @Test
    fun `metadata no-expiry와 expired expiry 상태를 구분한다`() {
        val noExpiry = parseDynamoDbMetadataItem(
            schema,
            mapOf(
                "id" to AttributeValue.S("metadata-key"),
                "value" to AttributeValue.S("payload"),
            ),
            nowEpochSeconds = 100,
        )
        val expired = parseDynamoDbMetadataItem(
            schema,
            mapOf(
                "id" to AttributeValue.S("metadata-key"),
                "value" to AttributeValue.S("payload"),
                "expiresAt" to AttributeValue.N("100"),
                "ttlEpochSeconds" to AttributeValue.N("100"),
            ),
            nowEpochSeconds = 100,
        )

        noExpiry.expiresAtEpochSeconds shouldBeEqualTo null
        noExpiry.expired.shouldBeFalse()
        expired.expiresAtEpochSeconds shouldBeEqualTo 100L
        expired.expired.shouldBeTrue()
    }

    @Test
    fun `metadata parser는 빈 값과 350000-byte value를 보존한다`() {
        val maximum = "x".repeat(DynamoDbCoordinationSchema.MAX_METADATA_VALUE_UTF8_BYTES)

        parseDynamoDbMetadataItem(
            schema,
            mapOf(
                "id" to AttributeValue.S("metadata-key"),
                "value" to AttributeValue.S(maximum),
            ),
            nowEpochSeconds = 100,
        ).value.length shouldBeEqualTo DynamoDbCoordinationSchema.MAX_METADATA_VALUE_UTF8_BYTES

        parseDynamoDbMetadataItem(
            schema,
            mapOf(
                "id" to AttributeValue.S("metadata-key"),
                "value" to AttributeValue.S(""),
            ),
            nowEpochSeconds = 100,
        ).value shouldBeEqualTo ""
    }

    @Test
    fun `logical operation당 resolver를 한 번만 호출한다`() {
        val calls = AtomicInteger()
        val resolvingSchema = DynamoDbCoordinationSchema(
            tableName = "coordination",
            namespace = "orders",
            resolver = DynamoDbCoordinationNameResolver { _, kind, key ->
                calls.incrementAndGet()
                "${kind.name.lowercase()}:$key"
            },
        )
        val resolved = resolvingSchema.resolve(DynamoDbCoordinationEntryKind.LOCK, "order-1")
        val lease = LockLease(
            key = "order-1",
            ownerId = "worker-1",
            fencingToken = 1L,
            expiresAtEpochSeconds = 101L,
            tableName = resolvingSchema.tableName,
            partitionKeyAttributeName = resolvingSchema.partitionKeyAttributeName,
            namespace = resolvingSchema.namespace,
            physicalKey = resolved.physicalKey,
            scopeId = resolved.scopeId,
        )

        calls.set(0)
        resolvingSchema.requireLeaseScope(lease)

        calls.get() shouldBeEqualTo 1
        DynamoDbCoordinationExpressions.LOCK_ACQUIRE_UPDATE shouldBeEqualTo
                "SET #owner = :owner, #expiresAt = :expiresAt, " +
                "#fencingToken = if_not_exists(#fencingToken, :zero) + :one"
        DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION shouldBeEqualTo
                "attribute_not_exists(#pk)"
    }

    @Test
    fun `fixed clock과 expiry overflow를 검증한다`() {
        val clock = Clock.fixed(Instant.parse("2026-08-27T00:00:42Z"), ZoneOffset.UTC)

        coordinationNowEpochSeconds(clock) shouldBeEqualTo 1_787_788_842L
        dynamoDbCoordinationExpiryEpochSeconds(1_000L, 30.seconds) shouldBeEqualTo 1_030L

        assertFailsWith<IllegalArgumentException> {
            dynamoDbCoordinationExpiryEpochSeconds(Long.MAX_VALUE, 1.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            dynamoDbCoordinationExpiryEpochSeconds(-1L, 1.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            coordinationNowEpochSeconds(Clock.fixed(Instant.ofEpochSecond(-1), ZoneOffset.UTC))
        }
    }

    @Test
    fun `AllOld 부재와 non-canonical persisted number는 fail closed한다`() {
        assertFailsWith<IllegalStateException> {
            requireDynamoDbCoordinationOldItem(null, operation = "acquire")
        }

        val malformed = mapOf(
            "id" to AttributeValue.S("lock-key"),
            "expiresAt" to AttributeValue.N("01"),
            "fencingToken" to AttributeValue.N("1"),
        )
        assertFailsWith<IllegalStateException> {
            parseDynamoDbLockItem(schema, malformed, nowEpochSeconds = 100)
        }

        assertFailsWith<IllegalStateException> {
            parseDynamoDbLockItem(
                schema,
                malformed + ("expiresAt" to AttributeValue.N("100")) +
                    ("fencingToken" to AttributeValue.N("0")),
                nowEpochSeconds = 100,
            )
        }

        val oldItem = mapOf("id" to AttributeValue.S("lock-key"))
        requireDynamoDbCoordinationOldItem(oldItem, operation = "acquire") shouldBeEqualTo oldItem
    }
}
