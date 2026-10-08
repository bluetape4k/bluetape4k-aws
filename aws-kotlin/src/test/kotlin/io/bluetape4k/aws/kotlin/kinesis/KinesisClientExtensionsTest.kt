package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import aws.sdk.kotlin.services.kinesis.model.StreamStatus
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.kotlin.kinesis.model.putRecordsRequestEntryOf
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.time.Duration.Companion.seconds

/**
 * AWS Kotlin SDK [aws.sdk.kotlin.services.kinesis.KinesisClient] 확장 함수 테스트.
 *
 * 스트림 생성 → ACTIVE 대기 → 레코드 전송 → 조회 → 삭제 순서로 테스트합니다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KinesisClientExtensionsTest: AbstractKotlinKinesisTest() {

    companion object: KLoggingChannel() {
        private val STREAM_NAME = "kotlin-test-stream-" + Base58.randomString(8).lowercase()
    }

    private lateinit var shardId: String
    private lateinit var shardIterator: String

    @Test
    @Order(1)
    fun `스트림 생성`() = runSuspendIO {
        withTestKinesisClient(localStackServer) { client ->
            val response = client.createStream(STREAM_NAME, shardCount = 1)
            log.debug { "createStream response=$response" }
            response.shouldNotBeNull()
        }
    }

    @Test
    @Order(2)
    fun `스트림 ACTIVE 상태 대기`() = runSuspendIO {
        withTestKinesisClient(localStackServer) { client ->
            var status: StreamStatus? = null

            await atMost 30.seconds withPollInterval 1.seconds untilSuspending {
                val desc = client.describeStream(STREAM_NAME)
                status = desc.streamDescription?.streamStatus
                status == StreamStatus.Active
            }

            status.shouldNotBeNull()
        }
    }

    @Test
    @Order(3)
    fun `단일 레코드 전송`() = runSuspendIO {
        withTestKinesisClient(localStackServer) { client ->
            val data = "Hello Kotlin Kinesis!".toUtf8Bytes()
            val response = client.putRecord(STREAM_NAME, "partition-1", data)

            log.debug { "putRecords response=$response" }
            response.encryptionType.shouldBeNull()
            response.sequenceNumber.shouldNotBeEmpty()
            response.shardId.shouldNotBeEmpty()

            this@KinesisClientExtensionsTest.shardId = response.shardId
        }
    }

    @Test
    @Order(4)
    fun `복수 레코드 배치 전송`() = runSuspendIO {
        withTestKinesisClient(localStackServer) { client ->
            val entries = List(5) { i ->
                putRecordsRequestEntryOf(
                    partitionKey = "partition-$i",
                    data = "kotlin-message-$i".toByteArray()
                )
            }
            val response = client.putRecords(STREAM_NAME, entries)

            log.debug { "putRecords response=$response" }
            response.failedRecordCount shouldBeEqualTo 0
            response.records.shouldNotBeEmpty()
        }
    }

    @Test
    @Order(5)
    fun `샤드 이터레이터 조회`() = runSuspendIO {
        withTestKinesisClient(localStackServer) { client ->
            val response = client.getShardIterator(STREAM_NAME, shardId, ShardIteratorType.TrimHorizon)

            this@KinesisClientExtensionsTest.shardIterator = response.shardIterator.shouldNotBeNull()
            shardIterator.shouldNotBeEmpty()
            log.debug { "shardIterator=$shardIterator" }
        }
    }

    @Test
    @Order(6)
    fun `레코드 조회`() = runSuspendIO {
        withTestKinesisClient(localStackServer) { client ->
            val response = client.getRecords(shardIterator, limit = 100)
            log.debug { "getRecords response=$response" }

            response.records.forEach { record ->
                log.debug { "record partitionKey=${record.partitionKey}, data=${record.data.decodeToString()}" }
            }
        }
    }

    @Test
    @Order(7)
    fun `스트림 삭제`() = runSuspendIO {
        withTestKinesisClient(localStackServer) { client ->
            val response = client.deleteStream(STREAM_NAME)
            log.debug { "deleteStream response=$response" }
            response.shouldNotBeNull()
        }
    }
}
