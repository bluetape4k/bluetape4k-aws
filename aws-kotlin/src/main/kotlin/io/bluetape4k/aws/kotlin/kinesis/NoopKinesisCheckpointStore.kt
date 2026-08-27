package io.bluetape4k.aws.kotlin.kinesis

/**
 * checkpoint를 저장하지 않는 process-local 구현입니다.
 *
 * 재시작 at-least-once, durable `ShardEnd`, 다중 worker fencing이 필요하면
 * 호출자가 영속 [KinesisCheckpointStore]를 주입해야 합니다.
 */
object NoopKinesisCheckpointStore : KinesisCheckpointStore {
    override suspend fun load(key: KinesisShardKey): KinesisCheckpoint? = null

    override suspend fun save(key: KinesisShardKey, checkpoint: KinesisCheckpoint, lease: KinesisLease) = Unit
}
