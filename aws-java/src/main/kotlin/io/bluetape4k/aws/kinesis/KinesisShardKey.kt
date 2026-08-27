package io.bluetape4k.aws.kinesis

import java.io.ObjectInputStream
import java.io.Serializable

/**
 * consumer group과 stream generation을 포함한 durable shard 상태 키입니다.
 *
 * `streamName`은 AWS API 입력이고 [streamIdentity]는 호출자가 안정적으로 유지해야 하는
 * 저장소 namespace입니다. canonical 표현은 길이 접두사를 사용해 delimiter 충돌을 막습니다.
 */
data class KinesisShardKey(
    val streamIdentity: String,
    val consumerGroup: String,
    val shardId: String,
) : Serializable {

    init {
        streamIdentity.requireKinesisIdentifier("streamIdentity")
        consumerGroup.requireKinesisIdentifier("consumerGroup")
        shardId.requireKinesisIdentifier("shardId")
    }

    /** 저장소 adapter가 사용할 delimiter 충돌 없는 canonical key입니다. */
    val canonicalValue: String
        get() = listOf(streamIdentity, consumerGroup, shardId)
            .joinToString("") { "${it.length}:$it" }

    /** 이전 초안 이름과의 source compatibility를 위한 alias입니다. */
    @Deprecated("Use canonicalValue", ReplaceWith("canonicalValue"))
    val canonical: String
        get() = canonicalValue

    @Suppress("unused")
    private fun readObject(input: ObjectInputStream) {
        input.defaultReadObject()
        streamIdentity.requireKinesisIdentifier("streamIdentity")
        consumerGroup.requireKinesisIdentifier("consumerGroup")
        shardId.requireKinesisIdentifier("shardId")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
