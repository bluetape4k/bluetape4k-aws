package io.bluetape4k.aws.kotlin.dynamodb.coordination

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class DynamoDbCoordinationSchemaTest {

    @Test
    fun `default resolver는 delimiter가 있는 tuple을 injective하게 인코딩한다`() {
        val resolver = DynamoDbCoordinationNameResolver.DEFAULT

        val first = resolver.resolve("tenant:a", DynamoDbCoordinationEntryKind.LOCK, "key|one")
        val second = resolver.resolve("tenant", DynamoDbCoordinationEntryKind.LOCK, "a:key|one")
        val metadata = resolver.resolve("tenant:a", DynamoDbCoordinationEntryKind.METADATA, "key|one")

        first shouldBeEqualTo resolver.resolve("tenant:a", DynamoDbCoordinationEntryKind.LOCK, "key|one")
        (first != second).shouldBeTrue()
        (first != metadata).shouldBeTrue()
    }

    @Test
    fun `custom resolver 결과와 table 및 attribute 이름을 사전 검증한다`() {
        val schema = DynamoDbCoordinationSchema(
            tableName = "coordination-table",
            namespace = "orders",
            resolver = DynamoDbCoordinationNameResolver { namespace, kind, logicalKey ->
                "$namespace/${kind.name}/$logicalKey"
            },
        )

        schema.tableName shouldBeEqualTo "coordination-table"
        schema.namespace shouldBeEqualTo "orders"
        schema.resolve(DynamoDbCoordinationEntryKind.LOCK, "order-1").physicalKey shouldBeEqualTo
                "orders/LOCK/order-1"

        assertFailsWith<IllegalArgumentException> {
            DynamoDbCoordinationSchema(tableName = "ab")
        }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbCoordinationSchema(tableName = "coordination", partitionKeyAttributeName = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbCoordinationSchema(
                tableName = "coordination",
                ownerAttributeName = "expiresAt",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbCoordinationSchema(
                tableName = "coordination",
                resolver = DynamoDbCoordinationNameResolver { _, _, _ -> "\u0000" },
            ).resolve(DynamoDbCoordinationEntryKind.LOCK, "key")
        }
    }

    @Test
    fun `identifier와 metadata value의 UTF-8 상한을 적용한다`() {
        val schema = DynamoDbCoordinationSchema(tableName = "coordination")
        val identifier = "가".repeat(DynamoDbCoordinationSchema.MAX_IDENTIFIER_UTF8_BYTES / 3 + 1)

        assertFailsWith<IllegalArgumentException> {
            schema.resolve(DynamoDbCoordinationEntryKind.LOCK, identifier)
        }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbCoordinationSchema(
                tableName = "coordination",
                namespace = identifier,
            )
        }
        DynamoDbCoordinationSchema.validateMetadataValue(
            "x".repeat(DynamoDbCoordinationSchema.MAX_METADATA_VALUE_UTF8_BYTES),
        )
        assertFailsWith<IllegalArgumentException> {
            DynamoDbCoordinationSchema.validateMetadataValue(
                "x".repeat(DynamoDbCoordinationSchema.MAX_METADATA_VALUE_UTF8_BYTES + 1),
            )
        }
    }

    @Test
    fun `duration은 정수 초와 365일 범위만 허용한다`() {
        DynamoDbCoordinationOptions(defaultLeaseDuration = 1.seconds)
        DynamoDbCoordinationOptions(defaultLeaseDuration = 365.days)

        assertFailsWith<IllegalArgumentException> {
            DynamoDbCoordinationOptions(defaultLeaseDuration = 0.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbCoordinationOptions(defaultLeaseDuration = 500.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbCoordinationOptions(defaultLeaseDuration = 366.days)
        }
    }

    @Test
    fun `scopeId는 schema identity를 안정적으로 표현한다`() {
        val clock = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC)
        val first = DynamoDbCoordinationSchema(
            tableName = "coordination",
            namespace = "orders",
            valueAttributeName = "payload-a",
        )
        val second = DynamoDbCoordinationSchema(
            tableName = "coordination",
            namespace = "orders",
            valueAttributeName = "payload-b",
        )
        val options = DynamoDbCoordinationOptions(clock = clock)

        first.lockScopeId shouldBeEqualTo first.lockScopeId
        (first.lockScopeId != second.lockScopeId).shouldBeFalse()
        options.clock.instant() shouldBeEqualTo clock.instant()
        first.resolve(DynamoDbCoordinationEntryKind.LOCK, "order-1").scopeId shouldBeEqualTo first.lockScopeId
    }
}
