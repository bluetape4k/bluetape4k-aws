package io.bluetape4k.aws.kotlin.dynamodb.coordination

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.ReturnValue
import aws.sdk.kotlin.services.dynamodb.model.ReturnValuesOnConditionCheckFailure
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemResponse
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class DynamoDbDistributedLockUnitTest {

    private val client = mockk<DynamoDbClient>()
    private val clock = Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC)
    private val schema = DynamoDbCoordinationSchema(tableName = "coordination", namespace = "orders")
    private val options = DynamoDbCoordinationOptions(defaultLeaseDuration = 5.seconds, clock = clock)
    private val lock = DynamoDbDistributedLock(client, schema, options)

    @BeforeEach
    fun resetMocks() {
        clearMocks(client)
    }

    @Test
    fun `new acquire 성공은 SDK 호출 한 번과 새 token을 반환한다`() = runTest {
        coEvery { client.updateItem(any<UpdateItemRequest>()) } returns UpdateItemResponse {
            attributes = lockItem("worker-1", 105, 1)
        }

        val lease = lock.tryAcquire("order-1", "worker-1", 5.seconds)

        lease?.fencingToken shouldBeEqualTo 1L
        lease?.expiresAtEpochSeconds shouldBeEqualTo 105L
        coVerify(exactly = 1) { client.updateItem(any<UpdateItemRequest>()) }
        coVerify(exactly = 1) {
            client.updateItem(match { request ->
                request.conditionExpression == "attribute_not_exists(#pk)" &&
                    request.updateExpression == DynamoDbCoordinationExpressions.LOCK_ACQUIRE_UPDATE &&
                    request.returnValues == ReturnValue.AllNew &&
                    request.returnValuesOnConditionCheckFailure == ReturnValuesOnConditionCheckFailure.AllOld &&
                    request.expressionAttributeValues?.get(":owner") == AttributeValue.S("worker-1")
            })
        }
    }

    @Test
    fun `active lock acquire는 AllOld 검증 후 null이고 재시도하지 않는다`() = runTest {
        val old = lockItem("other", 110, 3)
        coEvery { client.updateItem(any<UpdateItemRequest>()) } throws conditionalFailure(old)

        lock.tryAcquire("order-1", "worker-1", 5.seconds).shouldBeNull()

        coVerify(exactly = 1) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `expired takeover는 관찰 owner expiry token equality로 두 번째 호출만 한다`() = runTest {
        val old = lockItem("old-worker", 100, 3)
        coEvery { client.updateItem(any<UpdateItemRequest>()) } coAnswers {
            if (
                firstArg<UpdateItemRequest>().conditionExpression ==
                    DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
            ) {
                throw conditionalFailure(old)
            }
            UpdateItemResponse { attributes = lockItem("worker-1", 105, 4) }
        }

        val lease = lock.tryAcquire("order-1", "worker-1", 5.seconds)

        lease?.fencingToken shouldBeEqualTo 4L
        coVerify(exactly = 2) { client.updateItem(any<UpdateItemRequest>()) }
        coVerify(exactly = 1) {
            client.updateItem(match { request ->
                request.conditionExpression?.contains("#owner = :observedOwner") == true &&
                    request.conditionExpression?.contains("#expiresAt = :observedExpiresAt") == true &&
                    request.conditionExpression?.contains("#fencingToken = :observedToken") == true &&
                    request.conditionExpression?.contains("#expiresAt <= :now") == true &&
                    request.conditionExpression?.contains("#fencingToken < :maxToken") == true &&
                    request.expressionAttributeValues?.get(":observedOwner") == AttributeValue.S("old-worker") &&
                    request.expressionAttributeValues?.get(":observedExpiresAt") == AttributeValue.N("100") &&
                    request.expressionAttributeValues?.get(":observedToken") == AttributeValue.N("3") &&
                    request.expressionAttributeValues?.get(":now") == AttributeValue.N("100") &&
                    request.expressionAttributeValues?.get(":maxToken") == AttributeValue.N(Long.MAX_VALUE.toString())
            })
        }
    }

    @Test
    fun `takeover race의 두 번째 conditional failure는 null이고 loop를 만들지 않는다`() = runTest {
        val old = lockItem("old-worker", 100, 3)
        coEvery { client.updateItem(any<UpdateItemRequest>()) } returnsMany emptyList()
        coEvery { client.updateItem(any<UpdateItemRequest>()) } coAnswers {
            if (
                firstArg<UpdateItemRequest>().conditionExpression ==
                    DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
            ) {
                throw conditionalFailure(old)
            }
            throw conditionalFailure(old)
        }

        lock.tryAcquire("order-1", "worker-1", 5.seconds).shouldBeNull()
        coVerify(exactly = 2) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `renew과 heartbeat은 stale lease에서 각각 null을 반환한다`() = runTest {
        val lease = lease(105, 3)
        coEvery { client.updateItem(any<UpdateItemRequest>()) } throws conditionalFailure(
            lockItem("worker-1", 104, 3),
        )

        lock.renew(lease, 5.seconds).shouldBeNull()
        lock.heartbeat(lease, 5.seconds).shouldBeNull()

        coVerify(exactly = 2) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `renew 성공은 owner token previous expiry equality를 사용한다`() = runTest {
        val lease = lease(105, 3)
        coEvery { client.updateItem(any<UpdateItemRequest>()) } returns UpdateItemResponse {
            attributes = lockItem("worker-1", 110, 3)
        }

        lock.renew(lease, 10.seconds).shouldNotBeNull()

        coVerify(exactly = 1) {
            client.updateItem(match { request ->
                request.conditionExpression?.contains("#owner = :owner") == true &&
                    request.conditionExpression?.contains("#fencingToken = :token") == true &&
                    request.conditionExpression?.contains("#expiresAt = :previousExpiresAt") == true &&
                    request.conditionExpression?.contains("#expiresAt > :now") == true &&
                    request.expressionAttributeValues?.get(":owner") == AttributeValue.S("worker-1") &&
                    request.expressionAttributeValues?.get(":token") == AttributeValue.N("3") &&
                    request.expressionAttributeValues?.get(":previousExpiresAt") == AttributeValue.N("105") &&
                    request.expressionAttributeValues?.get(":now") == AttributeValue.N("100")
            })
        }
    }

    @Test
    fun `release는 stale lease에서 false를 반환하고 DeleteItem을 호출하지 않는다`() = runTest {
        val lease = lease(99, 3)
        coEvery { client.updateItem(any<UpdateItemRequest>()) } throws conditionalFailure(
            lockItem("worker-1", 99, 3),
        )

        lock.release(lease).shouldBeFalse()

        coVerify(exactly = 1) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `release 성공은 owner 제거와 now expiry를 요청하고 fencing token은 보존한다`() = runTest {
        val lease = lease(105, 3)
        coEvery { client.updateItem(any<UpdateItemRequest>()) } returns UpdateItemResponse {
            attributes = lockItem("worker-1", 105, 3)
        }

        lock.release(lease).shouldBeTrue()

        coVerify(exactly = 1) {
            client.updateItem(match { request ->
                request.updateExpression == "SET #expiresAt = :now REMOVE #owner" &&
                    request.returnValues == ReturnValue.AllOld &&
                    request.conditionExpression?.contains("#owner = :owner") == true &&
                    request.conditionExpression?.contains("#fencingToken = :token") == true &&
                    request.conditionExpression?.contains("#expiresAt = :previousExpiresAt") == true &&
                    request.expressionAttributeValues?.get(":token") == AttributeValue.N("3") &&
                    request.expressionAttributeValues?.get(":now") == AttributeValue.N("100")
            })
        }
    }

    @Test
    fun `token Long MAX_VALUE는 fencing token exhausted로 거부한다`() = runTest {
        coEvery { client.updateItem(any<UpdateItemRequest>()) } throws conditionalFailure(
            lockItem("worker-1", 100, Long.MAX_VALUE),
        )

        val failure = assertFailsWith<IllegalStateException> {
            lock.tryAcquire("order-1", "worker-2", 5.seconds)
        }
        failure.cause.shouldBeNull()
        coVerify(exactly = 1) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `token Long MAX_VALUE 직전에는 발급 전 exhaustion으로 second update를 막는다`() = runTest {
        coEvery { client.updateItem(any<UpdateItemRequest>()) } throws conditionalFailure(
            lockItem("worker-1", 100, Long.MAX_VALUE - 1),
        )

        assertFailsWith<IllegalStateException> {
            lock.tryAcquire("order-1", "worker-2", 5.seconds)
        }
        coVerify(exactly = 1) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `active lock은 token exhaustion 상태여도 현재 owner가 끝날 때까지 null이다`() = runTest {
        coEvery { client.updateItem(any<UpdateItemRequest>()) } throws conditionalFailure(
            lockItem("worker-1", 110, Long.MAX_VALUE - 1),
        )

        lock.tryAcquire("order-1", "worker-2", 5.seconds).shouldBeNull()

        coVerify(exactly = 1) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `AllNew fencing token이 기대값과 다르면 lease를 발급하지 않는다`() = runTest {
        coEvery { client.updateItem(any<UpdateItemRequest>()) } returns UpdateItemResponse {
            attributes = lockItem("worker-1", 105, 2)
        }

        assertFailsWith<IllegalStateException> {
            lock.tryAcquire("order-1", "worker-1", 5.seconds)
        }
        coVerify(exactly = 1) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `renew AllNew fencing token이 lease와 다르면 갱신 결과를 노출하지 않는다`() = runTest {
        val lease = lease(105, 3)
        coEvery { client.updateItem(any<UpdateItemRequest>()) } returns UpdateItemResponse {
            attributes = lockItem("worker-1", 110, 4)
        }

        assertFailsWith<IllegalStateException> {
            lock.renew(lease, 10.seconds)
        }
        coVerify(exactly = 1) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `takeover race의 malformed AllOld는 정상 race로 숨기지 않는다`() = runTest {
        val old = lockItem("old-worker", 100, 3)
        val malformed = old - "fencingToken"
        coEvery { client.updateItem(any<UpdateItemRequest>()) } coAnswers {
            if (
                firstArg<UpdateItemRequest>().conditionExpression ==
                    DynamoDbCoordinationExpressions.LOCK_KEY_ABSENT_CONDITION
            ) {
                throw conditionalFailure(old)
            }
            throw conditionalFailure(malformed)
        }

        assertFailsWith<IllegalStateException> {
            lock.tryAcquire("order-1", "worker-1", 5.seconds)
        }
        coVerify(exactly = 2) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `음수 clock은 release 전에 거부되어 malformed expiry를 기록하지 않는다`() = runTest {
        val negativeClockLock = DynamoDbDistributedLock(
            client,
            schema,
            DynamoDbCoordinationOptions(clock = Clock.fixed(Instant.ofEpochSecond(-1), ZoneOffset.UTC)),
        )

        assertFailsWith<IllegalArgumentException> {
            negativeClockLock.release(lease(105, 3))
        }
        coVerify(exactly = 0) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `CancellationException은 결과 매핑 없이 전달된다`() = runTest {
        val expected = CancellationException("cancelled")
        coEvery { client.updateItem(any<UpdateItemRequest>()) } throws expected

        val actual = assertFailsWith<CancellationException> {
            lock.tryAcquire("order-1", "worker-1", 5.seconds)
        }

        actual shouldBeEqualTo expected
    }

    @Test
    fun `scope mismatch는 DynamoDB 호출 없이 거부한다`() = runTest {
        val otherSchema = DynamoDbCoordinationSchema(tableName = "other-table", namespace = "orders")
        val otherResolved = otherSchema.resolve(DynamoDbCoordinationEntryKind.LOCK, "order-1")
        val lease = LockLease(
            key = "order-1",
            ownerId = "worker-1",
            fencingToken = 1,
            expiresAtEpochSeconds = 105,
            tableName = otherSchema.tableName,
            partitionKeyAttributeName = otherSchema.partitionKeyAttributeName,
            namespace = otherSchema.namespace,
            physicalKey = otherResolved.physicalKey,
            scopeId = otherResolved.scopeId,
        )

        assertFailsWith<IllegalArgumentException> { lock.renew(lease, 5.seconds) }
        coVerify(exactly = 0) { client.updateItem(any<UpdateItemRequest>()) }
    }

    @Test
    fun `convenience overload는 options default duration을 전달한다`() = runTest {
        coEvery { client.updateItem(any<UpdateItemRequest>()) } returns UpdateItemResponse {
            attributes = lockItem("worker-1", 105, 1)
        }

        lock.tryAcquire("order-1", "worker-1")

        coVerify(exactly = 1) {
            client.updateItem(match { request ->
                request.expressionAttributeValues?.get(":expiresAt") == AttributeValue.N("105")
            })
        }
    }

    private fun lease(expiry: Long, token: Long): LockLease {
        val resolved = schema.resolve(DynamoDbCoordinationEntryKind.LOCK, "order-1")
        return LockLease(
            key = "order-1",
            ownerId = "worker-1",
            fencingToken = token,
            expiresAtEpochSeconds = expiry,
            tableName = schema.tableName,
            partitionKeyAttributeName = schema.partitionKeyAttributeName,
            namespace = schema.namespace,
            physicalKey = resolved.physicalKey,
            scopeId = resolved.scopeId,
        )
    }

    private fun lockItem(owner: String, expiry: Long, token: Long): Map<String, AttributeValue> =
        mapOf(
            "id" to AttributeValue.S(schema.resolve(DynamoDbCoordinationEntryKind.LOCK, "order-1").physicalKey),
            "ownerId" to AttributeValue.S(owner),
            "expiresAt" to AttributeValue.N(expiry.toString()),
            "fencingToken" to AttributeValue.N(token.toString()),
        )

    private fun conditionalFailure(item: Map<String, AttributeValue>) = ConditionalCheckFailedException {
        this.item = item
    }
}
