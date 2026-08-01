package io.bluetape4k.aws.spring.kinesis

import kotlinx.coroutines.flow.Flow
import software.amazon.awssdk.services.kinesis.model.CreateStreamResponse
import software.amazon.awssdk.services.kinesis.model.DeleteStreamResponse
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse
import software.amazon.awssdk.services.kinesis.model.Record

/**
 * Spring 애플리케이션을 위한 코루틴 중심 Kinesis 작업입니다.
 *
 * ## 계약
 *
 * 메서드는 AWS SDK 응답 타입을 유지하므로 애플리케이션 코드에서 `CompletableFuture`를
 * 직접 처리하지 않으면서 서비스 메타데이터를 보존할 수 있습니다.
 *
 * ```kotlin
 * import software.amazon.awssdk.core.SdkBytes
 *
 * suspend fun publish(kinesis: KinesisOperations, payload: String) {
 *     kinesis.putRecord(
 *         KinesisPutRecordRequest(
 *             streamName = "orders",
 *             partitionKey = "order-1",
 *             data = SdkBytes.fromUtf8String(payload),
 *         )
 *     )
 * }
 * ```
 */
interface KinesisOperations {
    suspend fun createStream(streamName: String, shardCount: Int = 1): CreateStreamResponse
    suspend fun createConfiguredStream(streamName: String): CreateStreamResponse
    suspend fun deleteStream(streamName: String): DeleteStreamResponse
    suspend fun describeStream(streamName: String): DescribeStreamResponse
    suspend fun putRecord(request: KinesisPutRecordRequest): PutRecordResponse
    suspend fun putRecords(streamName: String, entries: List<PutRecordsRequestEntry>): PutRecordsResponse
    suspend fun getShardIterator(request: KinesisShardIteratorRequest): GetShardIteratorResponse
    suspend fun getRecords(shardIterator: String, limit: Int = 100): GetRecordsResponse
    fun recordFlow(request: KinesisRecordFlowRequest): Flow<Record>
}
