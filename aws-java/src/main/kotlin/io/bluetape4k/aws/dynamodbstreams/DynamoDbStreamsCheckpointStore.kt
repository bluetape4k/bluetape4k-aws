package io.bluetape4k.aws.dynamodbstreams

import java.util.concurrent.ConcurrentHashMap

/** DynamoDB Streams shard별 마지막 sequence number를 저장하는 SPI입니다. */
interface DynamoDbStreamsCheckpointStore {
    suspend fun load(streamArn: String, shardId: String): String?

    suspend fun save(streamArn: String, shardId: String, sequenceNumber: String)
}

/** checkpoint를 사용하지 않는 기본 저장소입니다. */
object NoopDynamoDbStreamsCheckpointStore : DynamoDbStreamsCheckpointStore {
    override suspend fun load(streamArn: String, shardId: String): String? = null

    override suspend fun save(streamArn: String, shardId: String, sequenceNumber: String) = Unit
}

/** 단위 테스트와 로컬 검증용 thread-safe in-memory checkpoint 저장소입니다. */
class InMemoryDynamoDbStreamsCheckpointStore : DynamoDbStreamsCheckpointStore {
    private val checkpoints = ConcurrentHashMap<String, String>()

    override suspend fun load(streamArn: String, shardId: String): String? = checkpoints[key(streamArn, shardId)]

    override suspend fun save(streamArn: String, shardId: String, sequenceNumber: String) {
        checkpoints[key(streamArn, shardId)] = sequenceNumber
    }

    private fun key(streamArn: String, shardId: String): String = "$streamArn\u0000$shardId"
}
