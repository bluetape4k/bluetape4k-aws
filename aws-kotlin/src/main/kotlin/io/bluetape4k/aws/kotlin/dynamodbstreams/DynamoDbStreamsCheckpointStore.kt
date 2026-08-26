package io.bluetape4k.aws.kotlin.dynamodbstreams

import java.util.concurrent.ConcurrentHashMap

/** DynamoDB Streams shard별 마지막으로 전달한 sequence number를 저장하는 SPI입니다. */
interface DynamoDbStreamsCheckpointStore {
    /** shard Flow 시작 시 저장된 checkpoint를 읽습니다. */
    suspend fun load(streamArn: String, shardId: String): String?

    /** record의 `emit`이 반환된 뒤 checkpoint를 저장합니다. */
    suspend fun save(streamArn: String, shardId: String, sequenceNumber: String)
}

/** checkpoint를 사용하지 않는 기본 저장소입니다. */
object NoopDynamoDbStreamsCheckpointStore : DynamoDbStreamsCheckpointStore {
    override suspend fun load(streamArn: String, shardId: String): String? = null

    override suspend fun save(streamArn: String, shardId: String, sequenceNumber: String) = Unit
}

/** 단위 테스트와 로컬 검증에 사용할 thread-safe in-memory checkpoint 저장소입니다. */
class InMemoryDynamoDbStreamsCheckpointStore : DynamoDbStreamsCheckpointStore {
    private val checkpoints = ConcurrentHashMap<String, String>()

    override suspend fun load(streamArn: String, shardId: String): String? = checkpoints[key(streamArn, shardId)]

    override suspend fun save(streamArn: String, shardId: String, sequenceNumber: String) {
        checkpoints[key(streamArn, shardId)] = sequenceNumber
    }

    private fun key(streamArn: String, shardId: String): String = "$streamArn\u0000$shardId"
}
