package io.bluetape4k.aws.kotlin.kinesis

import java.io.ObjectInputStream
import java.io.Serializable

/**
 * 한 worker가 샤드를 처리할 권리를 나타내는 fenced lease token입니다.
 *
 * [leaseCounter]는 takeover 때 증가합니다. checkpoint adapter는 저장 시점에
 * `key + ownerId + leaseCounter`를 조건으로 확인해 이전 owner의 stale write를 거부해야 합니다.
 */
data class KinesisLease(
    val key: KinesisShardKey,
    val ownerId: String,
    val leaseCounter: Long,
) : Serializable {

    init {
        ownerId.validateIdentifier("ownerId", KinesisShardKey.MAX_IDENTIFIER_LENGTH)
        require(leaseCounter > 0) {
            "leaseCounter must be positive, but was $leaseCounter"
        }
    }

    @Suppress("UnusedPrivateMember")
    private fun readObject(stream: ObjectInputStream) {
        stream.defaultReadObject()
        ownerId.validateIdentifier("ownerId", KinesisShardKey.MAX_IDENTIFIER_LENGTH)
        require(leaseCounter > 0) {
            "leaseCounter must be positive, but was $leaseCounter"
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
