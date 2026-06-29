package io.bluetape4k.aws.spring.kinesis

import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType
import java.io.Serializable

/**
 * Request for publishing one record to a Kinesis stream.
 *
 * ## Contract
 *
 * Wraps the same-typed stream and partition-key values in named properties so
 * callers do not accidentally swap positional arguments.
 */
data class KinesisPutRecordRequest(
    val streamName: String,
    val partitionKey: String,
    val data: SdkBytes,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Request for obtaining a shard iterator.
 *
 * ## Contract
 *
 * Mirrors the AWS SDK shard iterator request while keeping stream and shard
 * identifiers explicit for Spring application code.
 */
data class KinesisShardIteratorRequest(
    val streamName: String,
    val shardId: String,
    val type: ShardIteratorType = ShardIteratorType.TRIM_HORIZON,
    val startingSequenceNumber: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Request for collecting records from one Kinesis shard as a cold Flow.
 *
 * ## Contract
 *
 * The Flow is single-shard and caller-collected. This request does not imply
 * listener containers, lease coordination, or checkpoint persistence.
 */
data class KinesisRecordFlowRequest(
    val streamName: String,
    val shardId: String,
    val position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon,
    val options: KinesisRecordFlowOptions? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
