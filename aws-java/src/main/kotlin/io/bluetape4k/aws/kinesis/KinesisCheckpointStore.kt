package io.bluetape4k.aws.kinesis

/** lease fencing token과 함께 durable checkpoint를 조건부 저장하는 SPI입니다. */
interface KinesisCheckpointStore {
    suspend fun load(key: KinesisShardKey): KinesisCheckpoint?

    /** downstream emit 뒤에 호출하며 lease counter가 stale이면 실패해야 합니다. */
    suspend fun save(key: KinesisShardKey, checkpoint: KinesisCheckpoint, lease: KinesisLease)
}

/** checkpoint를 유지하지 않는 단일 프로세스 전용 저장소입니다. */
object NoopKinesisCheckpointStore : KinesisCheckpointStore {
    override suspend fun load(key: KinesisShardKey): KinesisCheckpoint? = null

    override suspend fun save(key: KinesisShardKey, checkpoint: KinesisCheckpoint, lease: KinesisLease) = Unit
}
