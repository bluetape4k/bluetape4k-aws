package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

/**
 * SQS 자동 설정 속성.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.sqs")
data class SqsProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val listener: Listener = Listener(),
    val queues: Map<String, Queue> = emptyMap(),
) : Serializable {
    data class Listener(
        val enabled: Boolean = true,
        val autoStartup: Boolean = true,
        val phase: Int = Int.MAX_VALUE,
        val maxMessages: Int = 10,
        val waitTimeSeconds: Int = 20,
        val visibilityTimeoutSeconds: Int? = null,
        val errorVisibilityTimeoutSeconds: Int? = null,
        /** 핸들러 실행 중 visibility를 연장할 heartbeat 호출 간격(초)입니다. */
        val messageVisibilityHeartbeatIntervalSeconds: Int? = null,
        /** heartbeat가 설정할 visibility timeout(초)입니다. */
        val messageVisibilityHeartbeatSeconds: Int? = null,
        val concurrency: Int = 1,
        val stopTimeoutMillis: Long = 25_000,
        val retry: Retry = Retry(),
    ) : Serializable {
        init {
            maxMessages.requireInRange(1, 10, "maxMessages")
            waitTimeSeconds.requireInRange(0, 20, "waitTimeSeconds")
            visibilityTimeoutSeconds?.let { requireVisibilityTimeout(it, "visibilityTimeoutSeconds") }
            errorVisibilityTimeoutSeconds?.let { requireVisibilityTimeout(it, "errorVisibilityTimeoutSeconds") }
            requireVisibilityHeartbeat(
                messageVisibilityHeartbeatIntervalSeconds,
                messageVisibilityHeartbeatSeconds,
            )
            concurrency.requireGe(1, "concurrency")
            stopTimeoutMillis.requireGe(1, "stopTimeoutMillis")
        }

        companion object {
            private const val serialVersionUID: Long = -3742913463973215849L
        }
    }

    /**
     * 최종 SQS 실패 경로 전에 적용하는 프로세스 내부 리스너 재시도 정책입니다.
     */
    data class Retry(
        val maxAttempts: Int = 1,
        val initialBackoff: Duration = Duration.ZERO,
        val maxBackoff: Duration? = null,
        val multiplier: Double = 2.0,
        val jitterRatio: Double = 0.0,
    ) : Serializable {
        init {
            maxAttempts.requireGe(1, "maxAttempts")
            initialBackoff.requireGe(Duration.ZERO, "initialBackoff")
            maxBackoff?.requireGe(Duration.ZERO, "maxBackoff")
            multiplier.requireGe(1.0, "multiplier")
            jitterRatio.requireInRange(0.0, 1.0, "jitterRatio")
        }

        companion object {
            private const val serialVersionUID: Long = 7867091222466790232L
        }
    }

    data class Queue(
        val url: String? = null,
        val redrivePolicy: RedrivePolicy? = null,
    ) : Serializable {
        companion object {
            private const val serialVersionUID: Long = -493034477206069305L
        }
    }

    data class RedrivePolicy(
        val deadLetterTargetArn: String,
        val maxReceiveCount: Int,
    ) : Serializable {
        init {
            deadLetterTargetArn.requireNotBlank("deadLetterTargetArn")
            maxReceiveCount.requireGe(1, "maxReceiveCount")
        }

        companion object {
            private const val serialVersionUID: Long = -1600650120598269377L
        }
    }

    companion object {
        private const val serialVersionUID: Long = -5777975012777169878L
    }
}

private fun requireVisibilityTimeout(value: Int, name: String) {
    value.requireInRange(0, 43_200, name)
}

internal fun requireVisibilityHeartbeat(
    intervalSeconds: Int?,
    heartbeatSeconds: Int?,
) {
    if (intervalSeconds == null && heartbeatSeconds == null) {
        return
    }
    require(intervalSeconds != null && heartbeatSeconds != null) {
        "messageVisibilityHeartbeatIntervalSeconds and messageVisibilityHeartbeatSeconds " +
            "must be configured together."
    }
    intervalSeconds.requireInRange(1, 43_200, "messageVisibilityHeartbeatIntervalSeconds")
    heartbeatSeconds.requireInRange(1, 43_200, "messageVisibilityHeartbeatSeconds")
    require(intervalSeconds < heartbeatSeconds) {
        "messageVisibilityHeartbeatIntervalSeconds must be less than messageVisibilityHeartbeatSeconds."
    }
}
