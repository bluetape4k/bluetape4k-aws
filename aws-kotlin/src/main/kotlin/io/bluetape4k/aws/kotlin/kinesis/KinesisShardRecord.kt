package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.model.Record

/**
 * Kinesis record와 그 logical stream/shard context를 함께 전달하는 envelope입니다.
 *
 * 서로 다른 shard 사이의 전역 순서는 보장하지 않으며, 한 shard 내부 순서는 AWS
 * `GetRecords` 응답 순서를 보존합니다. payload는 opaque 값으로 유지하고 library가
 * deserialize·execute·log하지 않습니다.
 */
data class KinesisShardRecord(
    val streamName: String,
    val shardId: String,
    val record: Record,
) {
    init {
        streamName.validateIdentifier("streamName", STREAM_NAME_MAX_LENGTH)
        shardId.validateIdentifier("shardId", KinesisShardKey.MAX_IDENTIFIER_LENGTH)
    }

    companion object {
        const val STREAM_NAME_MAX_LENGTH: Int = 128
    }
}
