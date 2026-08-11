package io.bluetape4k.aws.spring.sqs

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
            require(maxMessages in 1..10) { "maxMessages must be between 1 and 10." }
            require(waitTimeSeconds in 0..20) { "waitTimeSeconds must be between 0 and 20." }
            visibilityTimeoutSeconds?.let { requireVisibilityTimeout(it, "visibilityTimeoutSeconds") }
            errorVisibilityTimeoutSeconds?.let { requireVisibilityTimeout(it, "errorVisibilityTimeoutSeconds") }
            requireVisibilityHeartbeat(
                messageVisibilityHeartbeatIntervalSeconds,
                messageVisibilityHeartbeatSeconds,
            )
            require(concurrency >= 1) { "concurrency must be greater than or equal to 1." }
            require(stopTimeoutMillis >= 1) { "stopTimeoutMillis must be greater than or equal to 1." }
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
            require(maxAttempts >= 1) { "maxAttempts must be greater than or equal to 1." }
            require(!initialBackoff.isNegative) { "initialBackoff must not be negative." }
            maxBackoff?.let {
                require(!it.isNegative) { "maxBackoff must not be negative." }
            }
            require(multiplier >= 1.0) { "multiplier must be greater than or equal to 1.0." }
            require(jitterRatio in 0.0..1.0) { "jitterRatio must be between 0.0 and 1.0." }
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
            require(deadLetterTargetArn.isNotBlank()) { "deadLetterTargetArn must not be blank." }
            require(maxReceiveCount >= 1) { "maxReceiveCount must be greater than or equal to 1." }
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
    require(value in 0..43_200) { "$name must be between 0 and 43200." }
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
    require(intervalSeconds in 1..43_200) {
        "messageVisibilityHeartbeatIntervalSeconds must be between 1 and 43200."
    }
    require(heartbeatSeconds in 1..43_200) {
        "messageVisibilityHeartbeatSeconds must be between 1 and 43200."
    }
    require(intervalSeconds < heartbeatSeconds) {
        "messageVisibilityHeartbeatIntervalSeconds must be less than messageVisibilityHeartbeatSeconds."
    }
}
