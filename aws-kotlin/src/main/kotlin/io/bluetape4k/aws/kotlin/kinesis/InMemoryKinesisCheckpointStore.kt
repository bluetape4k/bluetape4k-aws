package io.bluetape4k.aws.kotlin.kinesis

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigInteger

/**
 * 단위 테스트와 Floci 계약 검증용 thread-safe checkpoint store입니다.
 *
 * lease token의 owner와 counter를 함께 보존해 stale save를 거부하고,
 * [KinesisCheckpoint.ShardEnd] 이후 sequence 역행도 차단합니다. 영속 adapter의
 * cross-process 원자성을 제공하는 구현은 아닙니다.
 */
class InMemoryKinesisCheckpointStore : KinesisCheckpointStore {

    private data class Entry(val checkpoint: KinesisCheckpoint, val lease: KinesisLease)

    private val mutex = Mutex()
    private val checkpoints = mutableMapOf<KinesisShardKey, Entry>()

    override suspend fun load(key: KinesisShardKey): KinesisCheckpoint? = mutex.withLock {
        checkpoints[key]?.checkpoint
    }

    @Suppress("ThrowsCount")
    override suspend fun save(key: KinesisShardKey, checkpoint: KinesisCheckpoint, lease: KinesisLease) {
        mutex.withLock {
            require(lease.key == key) {
                "lease key does not match checkpoint key: ${key.canonicalValue}"
            }
            val current = checkpoints[key]
            if (current != null) {
                if (lease.leaseCounter < current.lease.leaseCounter ||
                    (lease.leaseCounter == current.lease.leaseCounter && lease.ownerId != current.lease.ownerId)
                ) {
                    throw KinesisLeaseLostException(
                        "checkpoint save fenced for key=${key.canonicalValue}",
                    )
                }
                if (current.checkpoint is KinesisCheckpoint.ShardEnd && checkpoint is KinesisCheckpoint.Sequence) {
                    throw KinesisCheckpointException(
                        "cannot save Sequence after ShardEnd for key=${key.canonicalValue}",
                    )
                }
                if (current.checkpoint is KinesisCheckpoint.Sequence && checkpoint is KinesisCheckpoint.Sequence &&
                    compareSequence(checkpoint.sequenceNumber, current.checkpoint.sequenceNumber) < 0
                ) {
                    throw KinesisCheckpointException(
                        "checkpoint sequence moved backwards for key=${key.canonicalValue}",
                    )
                }
            }
            checkpoints[key] = Entry(checkpoint, lease)
        }
    }

    private fun compareSequence(left: String, right: String): Int {
        val leftNumber = left.toBigIntegerOrNull()
        val rightNumber = right.toBigIntegerOrNull()
        return if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            left.compareTo(right)
        }
    }

    private fun String.toBigIntegerOrNull(): BigInteger? = runCatching { BigInteger(this) }.getOrNull()
}
