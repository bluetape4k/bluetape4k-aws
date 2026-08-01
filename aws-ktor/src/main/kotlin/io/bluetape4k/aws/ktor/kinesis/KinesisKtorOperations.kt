package io.bluetape4k.aws.ktor.kinesis

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
 * Ktor 애플리케이션을 위한 코루틴 중심 Kinesis 작업입니다.
 *
 * ## 계약
 *
 * 작업은 호출자가 서비스 메타데이터와 항목별 실패 정보를 유지할 수 있도록 원본 AWS SDK
 * 응답 객체를 반환합니다. [recordFlow]는 명시적인 단일 샤드 cold 소비자 Flow이며
 * 임대나 체크포인트를 관리하지 않습니다.
 */
interface KinesisKtorOperations {
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
