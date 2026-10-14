package io.bluetape4k.aws.kinesis

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.aws.kinesis.model.putRecordsRequestEntry
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType
import software.amazon.awssdk.services.kinesis.model.StreamStatus
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [KinesisAsyncClient] 코루틴 확장 함수 테스트.
 *
 * 스트림 생성 → 레코드 전송 → 조회 → 삭제 순서로 테스트합니다.
 */
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KinesisAsyncClientCoroutinesExtensionsTest: AbstractKinesisTest() {

    companion object: KLoggingChannel() {
        private val STREAM_NAME = "test-async-stream-" + Base58.randomString(6).lowercase()
    }

    private lateinit var shardId: String
    private lateinit var shardIterator: String

    @Test
    @Order(1)
    fun `코루틴으로 스트림 생성`() = runSuspendIO {
        val response = asyncClient.createStream(STREAM_NAME, shardCount = 1)
        log.debug { "createStream response=$response" }
        response.sdkHttpResponse().isSuccessful.shouldBeTrue()
    }

    @Test
    @Order(2)
    fun `스트림 ACTIVE 상태 대기`() {
        await atMost 10.seconds withPollInterval 100.milliseconds until {
            val desc = client.describeStream(STREAM_NAME)
            val status = desc.streamDescription().streamStatus()

            log.debug { "stream=$STREAM_NAME status=$status" }
            status == StreamStatus.ACTIVE
        }
    }

    @Test
    @Order(3)
    fun `코루틴으로 단일 레코드 전송`() = runSuspendIO {
        val data = SdkBytes.fromUtf8String("Hello Kinesis Coroutines!")
        val response = asyncClient.putRecord(STREAM_NAME, "partition-1", data)

        response.sequenceNumber().shouldNotBeEmpty()
        response.shardId().shouldNotBeEmpty()
        shardId = response.shardId()
        log.debug { "putRecord sequenceNumber=${response.sequenceNumber()}, shardId=$shardId" }
    }

    @Test
    @Order(4)
    fun `코루틴으로 복수 레코드 배치 전송`() = runSuspendIO {
        val entries = List(5) { i ->
            putRecordsRequestEntry {
                partitionKey("partition-$i")
                data(SdkBytes.fromUtf8String("async-message-$i"))
            }
        }
        val response = asyncClient.putRecords(STREAM_NAME, entries)

        log.debug { "putRecords failedRecordCount=${response.failedRecordCount()}" }
        response.records().shouldNotBeEmpty()
        response.records().forEach { entry ->
            log.debug { "putRecord Result entry=$entry" }
        }
    }

    @Test
    @Order(5)
    fun `코루틴으로 샤드 이터레이터 조회`() = runSuspendIO {
        val response = asyncClient.getShardIterator(
            STREAM_NAME,
            shardId,
            ShardIteratorType.TRIM_HORIZON
        )
        log.debug { "response=$response" }

        shardIterator = response.shardIterator()
        shardIterator.shouldNotBeBlank()
        log.debug { "shardIterator=$shardIterator" }
    }

    @Test
    @Order(6)
    fun `코루틴으로 레코드 조회`() = runSuspendIO {
        val response = asyncClient.getRecords(shardIterator, limit = 100)

        log.debug { "response=$response" }
        log.debug { "records size=${response.records().size}" }
        response.records().shouldNotBeEmpty()

        response.records().forEach { record ->
            log.debug { "record partitionKey=${record.partitionKey()}, data=${record.data().asUtf8String()}" }
            record.partitionKey().shouldNotBeEmpty()
            record.data().asUtf8String().shouldNotBeEmpty()
        }
    }

    @Test
    @Order(7)
    fun `코루틴으로 스트림 삭제`() = runSuspendIO {
        val response = asyncClient.deleteStream(STREAM_NAME)
        response.sdkHttpResponse().isSuccessful.shouldBeTrue()
    }
}
