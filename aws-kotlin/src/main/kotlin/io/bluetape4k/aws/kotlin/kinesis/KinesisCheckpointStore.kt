package io.bluetape4k.aws.kotlin.kinesis

/**
 * Kinesis checkpoint를 저장하는 교체 가능한 SPI입니다.
 *
 * 영속 구현은 lease와 같은 consistency domain에서 조건부 저장을 수행해야 합니다.
 * 이 인터페이스 자체는 두 backend의 원자성을 대신 보장하지 않습니다.
 */
interface KinesisCheckpointStore {

    /** 샤드의 마지막 durable checkpoint를 읽습니다. */
    suspend fun load(key: KinesisShardKey): KinesisCheckpoint?

    /** 현재 lease token으로 checkpoint를 fenced 저장합니다. */
    suspend fun save(key: KinesisShardKey, checkpoint: KinesisCheckpoint, lease: KinesisLease)
}
