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

internal object NoopKinesisOperations : KinesisOperations {
    override suspend fun createStream(streamName: String, shardCount: Int): CreateStreamResponse =
        error("NoopKinesisOperations should not be called.")

    override suspend fun createConfiguredStream(streamName: String): CreateStreamResponse =
        error("NoopKinesisOperations should not be called.")

    override suspend fun deleteStream(streamName: String): DeleteStreamResponse =
        error("NoopKinesisOperations should not be called.")

    override suspend fun describeStream(streamName: String): DescribeStreamResponse =
        error("NoopKinesisOperations should not be called.")

    override suspend fun putRecord(request: KinesisPutRecordRequest): PutRecordResponse =
        error("NoopKinesisOperations should not be called.")

    override suspend fun putRecords(
        streamName: String,
        entries: List<PutRecordsRequestEntry>,
    ): PutRecordsResponse =
        error("NoopKinesisOperations should not be called.")

    override suspend fun getShardIterator(request: KinesisShardIteratorRequest): GetShardIteratorResponse =
        error("NoopKinesisOperations should not be called.")

    override suspend fun getRecords(shardIterator: String, limit: Int): GetRecordsResponse =
        error("NoopKinesisOperations should not be called.")

    override fun recordFlow(request: KinesisRecordFlowRequest): Flow<Record> =
        error("NoopKinesisOperations should not be called.")
}
