package io.bluetape4k.aws.kinesis

import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * thread-safe process-local checkpoint 저장소입니다.
 *
 * lease counter와 owner를 fencing하고, sequence는 역행하지 않게 저장합니다. 영속 adapter가
 * restart와 다중 worker를 지원하려면 lease/checkpoint/ShardEnd 조건부 commit을 하나의
 * consistency domain에서 구현해야 합니다.
 */
class InMemoryKinesisCheckpointStore : KinesisCheckpointStore {

    private val checkpoints = ConcurrentHashMap<KinesisShardKey, KinesisCheckpoint>()
    private val owners = ConcurrentHashMap<KinesisShardKey, KinesisLease>()
    private val mutex = Mutex()

    override suspend fun load(key: KinesisShardKey): KinesisCheckpoint? = checkpoints[key]

    @Suppress("ThrowsCount")
    override suspend fun save(key: KinesisShardKey, checkpoint: KinesisCheckpoint, lease: KinesisLease) {
        mutex.withLock {
            require(lease.key == key) { "lease key must match checkpoint key" }
            val previousLease = owners[key]
            when {
                previousLease == null -> Unit
                lease.leaseCounter < previousLease.leaseCounter -> throw KinesisLeaseLostException()
                lease.leaseCounter == previousLease.leaseCounter && lease.ownerId != previousLease.ownerId ->
                    throw KinesisLeaseLostException()
            }

            val previousCheckpoint = checkpoints[key]
            if (previousCheckpoint is KinesisCheckpoint.ShardEnd && checkpoint is KinesisCheckpoint.Sequence) {
                throw KinesisCheckpointException("ShardEnd checkpoint is terminal")
            }
            if (previousCheckpoint is KinesisCheckpoint.Sequence && checkpoint is KinesisCheckpoint.Sequence) {
                if (compareSequenceNumbers(checkpoint.sequenceNumber, previousCheckpoint.sequenceNumber) < 0) {
                    throw KinesisCheckpointException("checkpoint sequence must be monotonic")
                }
            }
            checkpoints[key] = checkpoint
            owners[key] = lease
        }
    }

    private fun compareSequenceNumbers(left: String, right: String): Int =
        runCatching { BigInteger(left).compareTo(BigInteger(right)) }
            .getOrElse { left.compareTo(right) }
}
