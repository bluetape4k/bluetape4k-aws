@file:Suppress("DEPRECATION", "LongMethod")

package io.bluetape4k.aws.kotlin.dynamodb.coordination

import aws.sdk.kotlin.services.dynamodb.deleteItem
import aws.sdk.kotlin.services.dynamodb.describeTable
import aws.sdk.kotlin.services.dynamodb.getItem
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.DynamoDbException
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.sdk.kotlin.services.dynamodb.model.KeyType
import aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType
import aws.sdk.kotlin.services.dynamodb.model.TimeToLiveSpecification
import aws.sdk.kotlin.services.dynamodb.model.UpdateTimeToLiveRequest
import aws.sdk.kotlin.services.dynamodb.updateTimeToLive
import aws.sdk.kotlin.services.dynamodb.putItem
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.kotlin.dynamodb.AbstractKotlinDynamoDbTest
import io.bluetape4k.aws.kotlin.dynamodb.createTable
import io.bluetape4k.aws.kotlin.dynamodb.deleteTableIfExists
import io.bluetape4k.aws.kotlin.dynamodb.existsTable
import io.bluetape4k.aws.kotlin.dynamodb.waitForTableReady
import io.bluetape4k.aws.kotlin.dynamodb.withDynamoDbClient
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.debug
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import aws.smithy.kotlin.runtime.SdkBaseException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 실제 AWS 없이 FlociServer에서 DynamoDB coordination 계약을 검증합니다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DynamoDbCoordinationFlociTest : AbstractKotlinDynamoDbTest() {

    private val tableName = "coord-${System.nanoTime()}"
    private val schema by lazy { DynamoDbCoordinationSchema(tableName = tableName, namespace = "floci-test") }
    private val lockOptions by lazy { DynamoDbCoordinationOptions(defaultLeaseDuration = 2.seconds) }

    @org.junit.jupiter.api.BeforeAll
    fun requireFloci() {
        assumeTrue(configuredAwsEmulatorName() == "floci", "#476 integration test는 FlociServer만 사용합니다")
    }

    @Test
    @Order(1)
    fun `Floci capability probe는 PK-only table과 conditional AllOld를 확인한다`() = runSuspendIO {
        withDynamoDbClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) {
            client ->
            client.deleteTableIfExists(tableName)
            client.createTable(tableName) {
                keySchema = listOf(KeySchemaElement { attributeName = "id"; keyType = KeyType.Hash })
                attributeDefinitions = listOf(AttributeDefinition {
                    attributeName = "id"
                    attributeType = ScalarAttributeType.S
                })
                provisionedThroughput {
                    readCapacityUnits = 5
                    writeCapacityUnits = 5
                }
            }
            client.waitForTableReady(tableName)

            val described = client.describeTable { this.tableName = this@DynamoDbCoordinationFlociTest.tableName }.table
            described?.keySchema?.size shouldBeEqualTo 1
            described?.keySchema?.single()?.keyType shouldBeEqualTo KeyType.Hash
            described?.keySchema?.single()?.attributeName shouldBeEqualTo "id"

            val probeKey = mapOf("id" to AttributeValue.S("all-old-probe"))
            client.putItem { this.tableName = this@DynamoDbCoordinationFlociTest.tableName; item = probeKey }
            try {
                client.putItem {
                    this.tableName = this@DynamoDbCoordinationFlociTest.tableName
                    item = probeKey + ("value" to AttributeValue.S("replacement"))
                    conditionExpression = "attribute_not_exists(#pk)"
                    expressionAttributeNames = mapOf("#pk" to "id")
                    returnValuesOnConditionCheckFailure =
                        aws.sdk.kotlin.services.dynamodb.model.ReturnValuesOnConditionCheckFailure.AllOld
                }
                error("Floci conditional probe unexpectedly succeeded")
            } catch (error: ConditionalCheckFailedException) {
                check(error.item != null) { "Floci did not return AllOld on conditional failure" }
            } finally {
                client.deleteItem { this.tableName = this@DynamoDbCoordinationFlociTest.tableName; this.key = probeKey }
            }

            // TTL 설정은 Floci 버전에 따라 아직 지원되지 않을 수 있다. logical expiry 계약은
            // ttl API 성공 여부와 독립적으로 검증하고, capability 결과는 로그에 남긴다.
            try {
                val ttlResponse = client.updateTimeToLive(
                    UpdateTimeToLiveRequest {
                        this.tableName = this@DynamoDbCoordinationFlociTest.tableName
                        timeToLiveSpecification = TimeToLiveSpecification {
                            attributeName = schema.ttlAttributeName
                            enabled = true
                        }
                    },
                )
                ttlResponse.timeToLiveSpecification?.attributeName shouldBeEqualTo schema.ttlAttributeName
                ttlResponse.timeToLiveSpecification?.enabled.shouldBeTrue()
                log.debug { "Floci TTL capability: supported" }
            } catch (error: UnsupportedOperationException) {
                log.debug { "Floci TTL capability: unavailable (${error::class.simpleName})" }
            } catch (error: SdkBaseException) {
                check(error is DynamoDbException && error.isKnownUnsupportedTtlFailure()) {
                    "Floci TTL capability probe failed unexpectedly: ${error::class.simpleName}"
                }
                log.debug { "Floci TTL capability: unavailable (${error::class.simpleName})" }
            }
        }
    }

    @Test
    @Order(2)
    fun `Floci lock은 contention renewal heartbeat release와 fencing takeover를 보장한다`() = runSuspendIO {
        withDynamoDbClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) {
            client ->
            val lock = DynamoDbDistributedLock(client, schema, lockOptions)
            val first = lock.tryAcquire("orders", "worker-a", 2.seconds)
            first.shouldNotBeNull()
            lock.tryAcquire("orders", "worker-b", 2.seconds).shouldBeNull()

            val firstLease = checkNotNull(first)
            val renewed = lock.renew(firstLease, 2.seconds)
            renewed.shouldNotBeNull()
            val renewedLease = checkNotNull(renewed)
            val heartbeated = lock.heartbeat(renewedLease, 2.seconds)
            heartbeated.shouldNotBeNull()
            val heartbeatedLease = checkNotNull(heartbeated)
            lock.release(heartbeatedLease).shouldBeTrue()

            val afterRelease = lock.tryAcquire("orders", "worker-b", 2.seconds)
            afterRelease.shouldNotBeNull()
            val afterReleaseLease = checkNotNull(afterRelease)
            afterReleaseLease.fencingToken shouldBeGreaterThan firstLease.fencingToken
            lock.release(afterReleaseLease).shouldBeTrue()

            val expiring = lock.tryAcquire("expiring", "worker-a", 1.seconds)
            expiring.shouldNotBeNull()
            val expiringLease = checkNotNull(expiring)
            delay(1_200.milliseconds)
            val takeover = lock.tryAcquire("expiring", "worker-b", 2.seconds)
            takeover.shouldNotBeNull()
            val takeoverLease = checkNotNull(takeover)
            takeoverLease.fencingToken shouldBeGreaterThan expiringLease.fencingToken
            lock.renew(expiringLease, 2.seconds).shouldBeNull()
            lock.release(expiringLease).shouldBeFalse()
            val currentLock = client.getItem {
                this.tableName = this@DynamoDbCoordinationFlociTest.tableName
                key = mapOf(
                    "id" to AttributeValue.S(
                        schema.resolve(DynamoDbCoordinationEntryKind.LOCK, "expiring").physicalKey,
                    ),
                )
            }.item
            currentLock?.get("ownerId") shouldBeEqualTo AttributeValue.S("worker-b")
            currentLock?.get("fencingToken") shouldBeEqualTo
                AttributeValue.N(takeoverLease.fencingToken.toString())
            currentLock?.containsKey("ttlEpochSeconds").shouldBeFalse()
            lock.release(takeoverLease).shouldBeTrue()
        }
    }

    @Test
    @Order(3)
    fun `Floci conditional lock 경쟁은 하나의 owner만 성공한다`() = runSuspendIO {
        withDynamoDbClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) {
            client ->
            val twoWay = compete(client, "contended-two", workers = 2)
            twoWay.count { it != null } shouldBeEqualTo 1
            twoWay.filterNotNull().forEach { lease ->
                DynamoDbDistributedLock(client, schema, lockOptions).release(lease).shouldBeTrue()
            }

            val eightWay = compete(client, "contended-eight", workers = 8)
            eightWay.count { it != null } shouldBeEqualTo 1
            eightWay.filterNotNull().forEach { lease ->
                DynamoDbDistributedLock(client, schema, lockOptions).release(lease).shouldBeTrue()
            }
        }
    }

    @Test
    @Order(4)
    fun `Floci metadata는 String overwrite logical expiry와 bounded remove를 보장한다`() = runSuspendIO {
        withDynamoDbClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) {
            client ->
            val metadataClock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
            val store = DynamoDbMetadataStore(
                client,
                schema,
                DynamoDbCoordinationOptions(clock = metadataClock),
            )
            store.put("config", "v1")
            store.get("config") shouldBeEqualTo "v1"
            store.put("config", "v2", 5.seconds)
            store.get("config") shouldBeEqualTo "v2"
            val rawMetadata = client.getItem {
                this.tableName = this@DynamoDbCoordinationFlociTest.tableName
                key = mapOf(
                    "id" to AttributeValue.S(
                        schema.resolve(DynamoDbCoordinationEntryKind.METADATA, "config").physicalKey,
                    ),
                )
            }.item.shouldNotBeNull()
            rawMetadata["ttlEpochSeconds"].shouldNotBeNull()
            rawMetadata["expiresAt"] shouldBeEqualTo rawMetadata["ttlEpochSeconds"]
            store.put("expiry-check", "v1", 5.seconds)
            store.get("expiry-check") shouldBeEqualTo "v1"
            DynamoDbMetadataStore(
                client,
                schema,
                DynamoDbCoordinationOptions(
                    clock = Clock.fixed(metadataClock.instant().plusSeconds(5), ZoneOffset.UTC),
                ),
            ).get("expiry-check").shouldBeNull()

            store.put("active-remove", "v1", 5.seconds)
            store.remove("active-remove").shouldBeTrue()
            store.put("active-cas", "v1", 5.seconds)
            store.removeIfValue("active-cas", "v1").shouldBeTrue()

            store.put("cas", "v1")
            store.putIfAbsent("cas", "v2").shouldBeFalse()
            store.removeIfValue("cas", "wrong").shouldBeFalse()
            store.removeIfValue("cas", "v1").shouldBeTrue()
            store.get("cas").shouldBeNull()
        }
    }

    @AfterAll
    fun `Floci table cleanup은 bounded wait로 완료한다`() = runSuspendIO {
        withContext(NonCancellable) {
            withTimeout(5.seconds) {
                withDynamoDbClient(
                    localStackServer.endpointUrl,
                    localStackServer.region,
                    localStackServer.credentialsProvider,
                ) {
                    client ->
                    client.deleteTableIfExists(tableName)
                    client.deleteTableIfExists(tableName)
                    while (client.existsTable(tableName)) {
                        delay(50.milliseconds)
                    }
                }
            }
        }
    }

    private suspend fun compete(
        client: DynamoDbClient,
        key: String,
        workers: Int,
    ): List<LockLease?> = coroutineScope {
        val ready = CompletableDeferred<Unit>()
        val start = CompletableDeferred<Unit>()
        val readyCount = AtomicInteger()
        val attempts = (1..workers).map { index ->
            async {
                if (readyCount.incrementAndGet() == workers) {
                    ready.complete(Unit)
                }
                start.await()
                DynamoDbDistributedLock(client, schema, lockOptions)
                    .tryAcquire(key, "worker-$index", 5.seconds)
            }
        }
        ready.await()
        start.complete(Unit)
        attempts.awaitAll()
    }

    private fun DynamoDbException.isKnownUnsupportedTtlFailure(): Boolean =
        message.contains("unsupported", ignoreCase = true) ||
            message.contains("not implemented", ignoreCase = true) ||
            message.contains("not supported", ignoreCase = true)
}
