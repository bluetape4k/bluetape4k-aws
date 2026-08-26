package io.bluetape4k.aws.kinesis

/** lease fencing 실패 또는 heartbeat가 ownership loss를 관측했을 때 발생합니다. */
class KinesisLeaseLostException(
    message: String = "Kinesis lease ownership was lost",
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** checkpoint 순서, terminal 상태 또는 저장 계약이 잘못되었을 때 발생합니다. */
class KinesisCheckpointException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** 완전한 shard 목록으로 일관된 dependency graph를 만들 수 없을 때 발생합니다. */
class KinesisShardGraphException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
