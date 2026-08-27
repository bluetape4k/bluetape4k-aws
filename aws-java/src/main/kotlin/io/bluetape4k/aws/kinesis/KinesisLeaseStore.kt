package io.bluetape4k.aws.kinesis

import kotlin.time.Duration

/** shard ownership을 counter 기반으로 획득·갱신·해제하는 교체 가능한 SPI입니다. */
interface KinesisLeaseStore {
    suspend fun acquire(key: KinesisShardKey, ownerId: String, leaseDuration: Duration): KinesisLease?

    suspend fun renew(lease: KinesisLease, leaseDuration: Duration): KinesisLease?

    /** stale owner의 release는 현재 owner lease를 삭제하지 않아야 합니다. */
    suspend fun release(lease: KinesisLease)
}

/** 다중 worker coordination을 제공하지 않는 단일 프로세스 전용 lease 저장소입니다. */
object NoopKinesisLeaseStore : KinesisLeaseStore {
    override suspend fun acquire(key: KinesisShardKey, ownerId: String, leaseDuration: Duration): KinesisLease =
        KinesisLease(key, ownerId.requireKinesisIdentifier("ownerId"), 1).also {
            require(leaseDuration.isPositive()) { "leaseDuration must be positive" }
        }

    override suspend fun renew(lease: KinesisLease, leaseDuration: Duration): KinesisLease = lease.also {
        require(leaseDuration.isPositive()) { "leaseDuration must be positive" }
    }

    override suspend fun release(lease: KinesisLease) = Unit
}
