package io.bluetape4k.aws.kotlin.kinesis

import kotlin.time.Duration

/**
 * Kinesis shard ownership를 관리하는 교체 가능한 SPI입니다.
 *
 * `null`을 반환하는 acquire/renew는 다른 worker가 lease를 보유하거나 만료된
 * token이 fenced 되었음을 뜻합니다. 영속 구현은 acquire/renew/release를 원자적으로
 * 처리해야 하며, consumer는 lease store의 lifecycle을 소유하지 않습니다.
 */
interface KinesisLeaseStore {

    /** 만료된 lease를 takeover하거나 비어 있는 shard를 획득합니다. */
    suspend fun acquire(key: KinesisShardKey, ownerId: String, leaseDuration: Duration): KinesisLease?

    /** 현재 owner와 counter가 일치할 때 lease를 연장합니다. */
    suspend fun renew(lease: KinesisLease, leaseDuration: Duration): KinesisLease?

    /** 현재 owner와 counter가 일치할 때만 lease를 해제합니다. */
    suspend fun release(lease: KinesisLease)
}
