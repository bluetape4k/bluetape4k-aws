package io.bluetape4k.aws.kinesis

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
) : Serializable {

    init {
        ownerId.requireKinesisIdentifier("ownerId")
        require(leaseCounter > 0) { "leaseCounter must be positive, but was $leaseCounter" }
    }

    @Suppress("unused")
    private fun readObject(input: ObjectInputStream) {
        input.defaultReadObject()
        ownerId.requireKinesisIdentifier("ownerId")
        require(leaseCounter > 0) { "leaseCounter must be positive, but was $leaseCounter" }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
