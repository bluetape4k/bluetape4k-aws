package io.bluetape4k.aws.spring.sqs

import java.io.Serializable

internal data class SqsListenerEndpoint(
    val id: String,
    val queue: String,
    val maxMessages: Int,
    val waitTimeSeconds: Int,
    val visibilityTimeoutSeconds: Int?,
    val errorVisibilityTimeoutSeconds: Int?,
    val autoStartup: Boolean,
    val phase: Int,
    val concurrency: Int,
    val stopTimeoutMillis: Long,
    val retry: SqsProperties.Retry,
    val batch: Boolean = false,
    val acknowledgementMode: SqsAcknowledgementMode = SqsAcknowledgementMode.INHERIT,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
