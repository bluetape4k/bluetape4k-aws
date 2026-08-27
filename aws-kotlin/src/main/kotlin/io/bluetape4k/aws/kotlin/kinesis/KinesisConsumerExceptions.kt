package io.bluetape4k.aws.kotlin.kinesis

/** Kinesis consumer runtime의 공통 오류입니다. */
open class KinesisConsumerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** heartbeat가 현재 worker의 lease 소유권을 잃었거나 fenced 저장이 거부된 경우입니다. */
class KinesisLeaseLostException(
    message: String = "Kinesis lease ownership was lost",
    cause: Throwable? = null,
) : KinesisConsumerException(message, cause)

/** checkpoint 형식, 순서 또는 durable terminal 규칙을 위반한 경우입니다. */
class KinesisCheckpointException(
    message: String,
    cause: Throwable? = null,
) : KinesisConsumerException(message, cause)

/** 완전한 shard 목록으로 일관된 dependency graph를 만들 수 없는 경우입니다. */
class KinesisShardGraphException(
    message: String,
    cause: Throwable? = null,
) : KinesisConsumerException(message, cause)
