package io.bluetape4k.aws.spring.sqs

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
)
