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
 * Coroutine-oriented Kinesis operations for Spring applications.
 *
 * ## Contract
 *
 * Methods keep AWS SDK response types so callers retain service metadata while
 * avoiding direct `CompletableFuture` handling in application code.
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
