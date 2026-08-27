package io.bluetape4k.aws.kotlin.dynamodb.coordination

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemResponse
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.GetItemResponse
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemResponse
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class DynamoDbMetadataStoreUnitTest {

    private val client = mockk<DynamoDbClient>()
    private val clock = Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC)
    private val schema = DynamoDbCoordinationSchema(tableName = "coordination", namespace = "orders")
    private val options = DynamoDbCoordinationOptions(clock = clock)
    private val store = DynamoDbMetadataStore(client, schema, options)

    @BeforeEach
    fun resetMocks() {
        clearMocks(client)
    }

    @Test
    fun `get은 consistentRead와 만료 logical null을 사용한다`() = runTest {
        coEvery { client.getItem(any<GetItemRequest>()) } returnsMany listOf(
            GetItemResponse { item = metadataItem("payload") },
            GetItemResponse { item = metadataItem("expired", expiry = 100) },
        )

        store.get("metadata-1") shouldBeEqualTo "payload"
        store.get("metadata-1").shouldBeNull()

        coVerify(exactly = 2) { client.getItem(any<GetItemRequest>()) }
        coVerify(exactly = 2) {
            client.getItem(match { request ->
                request.consistentRead == true && request.key?.get("id") ==
                    AttributeValue.S(schema.resolve(DynamoDbCoordinationEntryKind.METADATA, "metadata-1").physicalKey)
            })
        }
    }

    @Test
    fun `get의 consistentRead false와 no-expiry active item을 보존한다`() = runTest {
        val noConsistentStore = DynamoDbMetadataStore(
            client,
            schema,
            DynamoDbCoordinationOptions(consistentRead = false, clock = clock),
        )
        coEvery { client.getItem(any<GetItemRequest>()) } returns GetItemResponse { item = metadataItem("payload") }

        noConsistentStore.get("metadata-1") shouldBeEqualTo "payload"
        coVerify(exactly = 1) { client.getItem(match { it.consistentRead == false }) }
    }

    @Test
    fun `put은 ttl이 없으면 expiresAt과 ttlEpochSeconds를 제거한다`() = runTest {
        coEvery { client.putItem(any<PutItemRequest>()) } returns PutItemResponse {}

        store.put("metadata-1", "payload")

        coVerify(exactly = 1) {
            client.putItem(match { request ->
                request.item?.keys == setOf("id", "value") &&
                    request.item?.get("value") == AttributeValue.S("payload")
            })
        }
    }

    @Test
    fun `put은 ttl expiry와 ttlEpochSeconds를 동일하게 기록한다`() = runTest {
        coEvery { client.putItem(any<PutItemRequest>()) } returns PutItemResponse {}

        store.put("metadata-1", "payload", 5.seconds)

        coVerify(exactly = 1) {
            client.putItem(match { request ->
                request.item?.get("expiresAt") == AttributeValue.N("105") &&
                    request.item?.get("ttlEpochSeconds") == AttributeValue.N("105")
            })
        }
    }

    @Test
    fun `putIfAbsent active item은 false이고 expired item은 최대 두 호출로 교체한다`() = runTest {
        val active = metadataItem("active", expiry = 110)
        coEvery { client.putItem(any<PutItemRequest>()) } throws conditionalFailure(active)

        store.putIfAbsent("metadata-1", "new").shouldBeFalse()
        coVerify(exactly = 1) { client.putItem(any<PutItemRequest>()) }

        clearMocks(client)
        val expired = metadataItem("expired", expiry = 100)
        coEvery { client.putItem(any<PutItemRequest>()) } coAnswers {
            if (
                firstArg<PutItemRequest>().conditionExpression ==
                    DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
            ) {
                throw conditionalFailure(expired)
            }
            PutItemResponse {}
        }

        store.putIfAbsent("metadata-1", "new", 5.seconds).shouldBeTrue()
        coVerify(exactly = 2) { client.putItem(any<PutItemRequest>()) }
        coVerify(exactly = 1) {
            client.putItem(match { request ->
                request.conditionExpression == DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION &&
                    request.expressionAttributeNames == mapOf(
                        DynamoDbCoordinationExpressions.PK_ALIAS to schema.partitionKeyAttributeName,
                    )
            })
        }
        coVerify(exactly = 1) {
            client.putItem(match { request ->
                request.conditionExpression?.contains("#value = :observedValue") == true &&
                    request.conditionExpression?.contains("#expiresAt = :observedExpiresAt") == true &&
                    request.expressionAttributeValues?.get(":observedValue") == AttributeValue.S("expired")
            })
        }
    }

    @Test
    fun `expired metadata 교체 중 malformed race item은 false로 숨기지 않는다`() = runTest {
        val expired = metadataItem("expired", expiry = 100)
        val malformed = expired - "value"
        coEvery { client.putItem(any<PutItemRequest>()) } coAnswers {
            if (
                firstArg<PutItemRequest>().conditionExpression ==
                    DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
            ) {
                throw conditionalFailure(expired)
            }
            throw conditionalFailure(malformed)
        }

        assertFailsWith<IllegalStateException> {
            store.putIfAbsent("metadata-1", "new", 5.seconds)
        }
        coVerify(exactly = 2) { client.putItem(any<PutItemRequest>()) }
    }

    @Test
    fun `remove는 value expiry equality를 사용한다`() = runTest {
        val old = metadataItem("payload", expiry = 110)
        coEvery { client.deleteItem(any<DeleteItemRequest>()) } coAnswers {
            if (
                firstArg<DeleteItemRequest>().conditionExpression ==
                    DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
            ) {
                throw conditionalFailure(old)
            }
            DeleteItemResponse {}
        }

        store.remove("metadata-1").shouldBeTrue()

        coVerify(exactly = 2) { client.deleteItem(any<DeleteItemRequest>()) }
        coVerify(exactly = 1) {
            client.deleteItem(match { request ->
                request.conditionExpression == DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
            })
        }
        coVerify(exactly = 1) {
            client.deleteItem(match { request ->
                request.conditionExpression?.contains("#value = :observedValue") == true &&
                    request.conditionExpression?.contains("#expiresAt = :observedExpiresAt") == true &&
                    request.expressionAttributeValues?.get(":observedValue") == AttributeValue.S("payload") &&
                    request.expressionAttributeValues?.get(":observedExpiresAt") == AttributeValue.N("110")
            })
        }
    }

    @Test
    fun `active TTL metadata remove와 removeIfValue는 expiry 만료 조건 없이 삭제한다`() = runTest {
        val active = metadataItem("payload", expiry = 110)
        coEvery { client.deleteItem(any<DeleteItemRequest>()) } coAnswers {
            if (
                firstArg<DeleteItemRequest>().conditionExpression ==
                    DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
            ) {
                throw conditionalFailure(active)
            }
            DeleteItemResponse {}
        }

        store.remove("metadata-1").shouldBeTrue()
        clearMocks(client)
        coEvery { client.deleteItem(any<DeleteItemRequest>()) } coAnswers {
            if (
                firstArg<DeleteItemRequest>().conditionExpression ==
                    DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
            ) {
                throw conditionalFailure(active)
            }
            DeleteItemResponse {}
        }

        store.removeIfValue("metadata-1", "payload").shouldBeTrue()

        coVerify(exactly = 2) { client.deleteItem(any<DeleteItemRequest>()) }
        coVerify(exactly = 1) {
            client.deleteItem(match { request ->
                request.conditionExpression?.contains("#expiresAt = :observedExpiresAt") == true &&
                    request.conditionExpression?.contains("<= :now") == false &&
                    request.expressionAttributeValues?.containsKey(":now") == false
            })
        }
    }

    @Test
    fun `removeIfValue는 expected value가 다르면 second delete를 호출하지 않는다`() = runTest {
        coEvery { client.deleteItem(any<DeleteItemRequest>()) } throws conditionalFailure(metadataItem("actual"))

        store.removeIfValue("metadata-1", "expected").shouldBeFalse()

        coVerify(exactly = 1) { client.deleteItem(any<DeleteItemRequest>()) }
    }

    @Test
    fun `expired metadata cleanup 성공도 API 결과는 false다`() = runTest {
        val expired = metadataItem("payload", expiry = 100)
        coEvery { client.deleteItem(any<DeleteItemRequest>()) } coAnswers {
            if (
                firstArg<DeleteItemRequest>().conditionExpression ==
                    DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
            ) {
                throw conditionalFailure(expired)
            }
            DeleteItemResponse {}
        }

        store.remove("metadata-1").shouldBeFalse()
        coVerify(exactly = 2) { client.deleteItem(any<DeleteItemRequest>()) }
        coVerify(exactly = 1) {
            client.deleteItem(match { request ->
                request.conditionExpression?.contains("#expiresAt <= :now") == true &&
                    request.expressionAttributeValues?.get(":now") == AttributeValue.N("100")
            })
        }
    }

    @Test
    fun `no-expiry remove request는 두 expiry attribute 부재 조건을 캡처한다`() = runTest {
        coEvery { client.deleteItem(any<DeleteItemRequest>()) } coAnswers {
            if (
                firstArg<DeleteItemRequest>().conditionExpression ==
                    DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
            ) {
                throw conditionalFailure(metadataItem("payload"))
            }
            DeleteItemResponse {}
        }

        store.remove("metadata-1").shouldBeTrue()

        coVerify(exactly = 1) {
            client.deleteItem(match { request ->
                request.conditionExpression?.contains("attribute_not_exists(#expiresAt)") == true &&
                    request.conditionExpression?.contains("attribute_not_exists(#ttl)") == true
            })
        }
    }

    @Test
    fun `malformed metadata는 두 번째 mutation 전에 예외를 낸다`() = runTest {
        val malformed = metadataItem("payload", expiry = 100).toMutableMap()
        malformed["ttlEpochSeconds"] = AttributeValue.N("101")
        coEvery { client.deleteItem(any<DeleteItemRequest>()) } throws conditionalFailure(malformed)

        assertFailsWith<IllegalStateException> {
            store.remove("metadata-1")
        }
        coVerify(exactly = 1) { client.deleteItem(any<DeleteItemRequest>()) }
    }

    @Test
    fun `AllOld 부재는 putIfAbsent와 remove에서 unsupported로 fail closed한다`() = runTest {
        coEvery { client.putItem(any<PutItemRequest>()) } throws ConditionalCheckFailedException {}
        assertFailsWith<IllegalStateException> { store.putIfAbsent("metadata-1", "payload") }

        clearMocks(client)
        coEvery { client.deleteItem(any<DeleteItemRequest>()) } throws ConditionalCheckFailedException {}
        assertFailsWith<IllegalStateException> { store.remove("metadata-1") }
    }

    @Test
    fun `get의 malformed metadata는 null로 숨기지 않고 예외를 낸다`() = runTest {
        val malformed = metadataItem("payload").toMutableMap()
        malformed["expiresAt"] = AttributeValue.N("100.0")
        coEvery { client.getItem(any<GetItemRequest>()) } returns GetItemResponse { item = malformed }

        assertFailsWith<IllegalStateException> { store.get("metadata-1") }
    }

    @Test
    fun `metadata value와 ttl duration 입력 상한을 호출 전에 검증한다`() = runTest {
        val oversized = "x".repeat(DynamoDbCoordinationSchema.MAX_METADATA_VALUE_UTF8_BYTES + 1)

        assertFailsWith<IllegalArgumentException> { store.put("metadata-1", oversized) }
        assertFailsWith<IllegalArgumentException> { store.put("metadata-1", "payload", 0.seconds) }
        coVerify(exactly = 0) { client.putItem(any<PutItemRequest>()) }
    }

    @Test
    fun `metadata 최대 허용 value는 350000 bytes까지 보존한다`() = runTest {
        val maximum = "x".repeat(DynamoDbCoordinationSchema.MAX_METADATA_VALUE_UTF8_BYTES)
        coEvery { client.putItem(any<PutItemRequest>()) } returns PutItemResponse {}

        store.put("metadata-1", maximum)

        coVerify(exactly = 1) {
            client.putItem(match { request ->
                (request.item?.get("value") as? AttributeValue.S)?.value?.length ==
                    DynamoDbCoordinationSchema.MAX_METADATA_VALUE_UTF8_BYTES
            })
        }
    }

    @Test
    fun `putIfAbsent와 remove는 pre-read GetItem 없이 bounded 호출을 유지한다`() = runTest {
        coEvery { client.putItem(any<PutItemRequest>()) } returns PutItemResponse {}
        coEvery { client.deleteItem(any<DeleteItemRequest>()) } returns DeleteItemResponse {}

        store.putIfAbsent("metadata-1", "payload")
        store.remove("metadata-1")

        coVerify(exactly = 0) { client.getItem(any<GetItemRequest>()) }
    }

    private fun metadataItem(value: String, expiry: Long? = null): Map<String, AttributeValue> {
        val physicalKey = schema.resolve(DynamoDbCoordinationEntryKind.METADATA, "metadata-1").physicalKey
        return buildMap {
            put("id", AttributeValue.S(physicalKey))
            put("value", AttributeValue.S(value))
            expiry?.let {
                put("expiresAt", AttributeValue.N(it.toString()))
                put("ttlEpochSeconds", AttributeValue.N(it.toString()))
            }
        }
    }

    private fun conditionalFailure(item: Map<String, AttributeValue>) = ConditionalCheckFailedException {
        this.item = item
    }
}
