package io.bluetape4k.aws.kotlin.kinesis

import io.bluetape4k.support.requireNotBlank
import java.io.ObjectInputStream
import java.io.Serializable

/**
 * Kinesis consumer가 checkpoint와 lease를 공유할 때 사용하는 샤드 식별자입니다.
 *
 * `streamIdentity`는 스트림을 재생성해도 유지할 세대 식별자이고,
 * `consumerGroup`은 같은 스트림을 독립적으로 읽는 소비자 namespace입니다.
 * 두 값과 [shardId]는 raw delimiter를 이어 붙이지 않은 length-prefixed
 * [canonicalValue]로 저장하므로 tuple 경계가 충돌하지 않습니다.
 */
data class KinesisShardKey(
    val streamIdentity: String,
    val consumerGroup: String,
    val shardId: String,
) : Serializable {

    /** store adapter가 사용할 수 있는 deterministic tuple 표현입니다. */
    val canonicalValue: String = canonicalize(streamIdentity, consumerGroup, shardId)

    init {
        streamIdentity.validateIdentifier("streamIdentity", MAX_IDENTIFIER_LENGTH)
        consumerGroup.validateIdentifier("consumerGroup", MAX_IDENTIFIER_LENGTH)
        shardId.validateIdentifier("shardId", MAX_IDENTIFIER_LENGTH)
        require(canonicalValue == canonicalize(streamIdentity, consumerGroup, shardId)) {
            "canonicalValue does not match shard key components"
        }
    }

    @Suppress("UnusedPrivateMember")
    private fun readObject(stream: ObjectInputStream) {
        stream.defaultReadObject()
        streamIdentity.validateIdentifier("streamIdentity", MAX_IDENTIFIER_LENGTH)
        consumerGroup.validateIdentifier("consumerGroup", MAX_IDENTIFIER_LENGTH)
        shardId.validateIdentifier("shardId", MAX_IDENTIFIER_LENGTH)
        require(canonicalValue == canonicalize(streamIdentity, consumerGroup, shardId)) {
            "canonicalValue does not match shard key components"
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        /** Kinesis consumer 식별자에 허용하는 최대 문자 수입니다. */
        const val MAX_IDENTIFIER_LENGTH: Int = 256

        private fun canonicalize(vararg values: String): String = buildString {
            values.forEach { value ->
                append(value.length)
                append(':')
                append(value)
            }
        }
    }
}

/** caller 입력에 공통으로 적용하는 bounded identifier 검증입니다. */
internal fun String.validateIdentifier(name: String, maxLength: Int): String {
    requireNotBlank(name)
    require(length <= maxLength) {
        "$name length must be <= $maxLength, but was $length"
    }
    require(none { it.isISOControl() }) {
        "$name must not contain control characters"
    }
    return this
}
