package io.bluetape4k.aws.spring.sqs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

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
) {
    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.sqs.region is required when endpointOverride is configured."
        }
    }

    data class Listener(
        val enabled: Boolean = true,
        val autoStartup: Boolean = true,
        val phase: Int = Int.MAX_VALUE,
        val maxMessages: Int = 10,
        val waitTimeSeconds: Int = 20,
        val visibilityTimeoutSeconds: Int? = null,
        val errorVisibilityTimeoutSeconds: Int? = null,
        val concurrency: Int = 1,
        val stopTimeoutMillis: Long = 25_000,
    ) {
        init {
            require(maxMessages in 1..10) { "maxMessages must be between 1 and 10." }
            require(waitTimeSeconds in 0..20) { "waitTimeSeconds must be between 0 and 20." }
            visibilityTimeoutSeconds?.let { requireVisibilityTimeout(it, "visibilityTimeoutSeconds") }
            errorVisibilityTimeoutSeconds?.let { requireVisibilityTimeout(it, "errorVisibilityTimeoutSeconds") }
            require(concurrency >= 1) { "concurrency must be greater than or equal to 1." }
            require(stopTimeoutMillis >= 1) { "stopTimeoutMillis must be greater than or equal to 1." }
        }
    }

    data class Queue(
        val url: String? = null,
        val redrivePolicy: RedrivePolicy? = null,
    )

    data class RedrivePolicy(
        val deadLetterTargetArn: String,
        val maxReceiveCount: Int,
    ) {
        init {
            require(deadLetterTargetArn.isNotBlank()) { "deadLetterTargetArn must not be blank." }
            require(maxReceiveCount >= 1) { "maxReceiveCount must be greater than or equal to 1." }
        }
    }
}

private fun requireVisibilityTimeout(value: Int, name: String) {
    require(value in 0..43_200) { "$name must be between 0 and 43200." }
}
