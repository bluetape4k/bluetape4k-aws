package io.bluetape4k.aws.kinesis

import io.bluetape4k.support.requirePositiveNumber
import java.io.ObjectInputStream
import java.io.Serializable

/**
 * lease fencing token입니다. `key + ownerId + leaseCounter`를 조건부 저장에 함께 전달해야
 * stale worker가 checkpoint를 덮어쓰지 못합니다.
 */
data class KinesisLease(
    val key: KinesisShardKey,
    val ownerId: String,
    val leaseCounter: Long,
): Serializable {

    init {
        ownerId.requireKinesisIdentifier("ownerId")
        leaseCounter.requirePositiveNumber("leaseCounter")
    }

    @Suppress("unused")
    private fun readObject(input: ObjectInputStream) {
        input.defaultReadObject()
        ownerId.requireKinesisIdentifier("ownerId")
        leaseCounter.requirePositiveNumber("leaseCounter")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
