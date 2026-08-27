package io.bluetape4k.aws.kinesis

import software.amazon.awssdk.services.kinesis.model.Record

/** shard 식별자와 원본 AWS SDK v2 [Record]를 함께 전달하는 envelope입니다. */
data class KinesisShardRecord(
    val streamName: String,
    val shardId: String,
    val record: Record,
) {
    init {
        streamName.requireKinesisStreamName()
        shardId.requireKinesisIdentifier("shardId")
    }
}
