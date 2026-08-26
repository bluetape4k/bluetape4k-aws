package io.bluetape4k.aws.kotlin.kinesis

import kotlin.time.Duration

/**
 * 모든 요청을 process-local owner의 lease로 취급하는 구현입니다.
 *
 * 상태를 저장하지 않으므로 다중 worker 조정·expiry takeover·재시작 lease 보존을
 * 제공하지 않습니다. durable 운영에서는 영속 [KinesisLeaseStore]를 주입해야 합니다.
 */
object NoopKinesisLeaseStore : KinesisLeaseStore {
    override suspend fun acquire(key: KinesisShardKey, ownerId: String, leaseDuration: Duration): KinesisLease {
        ownerId.validateIdentifier("ownerId", KinesisShardKey.MAX_IDENTIFIER_LENGTH)
        require(leaseDuration.isPositive()) { "leaseDuration must be positive, but was $leaseDuration" }
        return KinesisLease(key, ownerId, 1)
    }

    override suspend fun renew(lease: KinesisLease, leaseDuration: Duration): KinesisLease? {
        require(leaseDuration.isPositive()) { "leaseDuration must be positive, but was $leaseDuration" }
        return lease
    }

    override suspend fun release(lease: KinesisLease) = Unit
}
