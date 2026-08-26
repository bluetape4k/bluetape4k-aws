package io.bluetape4k.aws.spring.sqs

import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.io.Serializable
import java.time.Duration

internal data class SqsListenerEndpoint(
    val id: String,
    val queue: String,
    val maxMessages: Int,
    val waitTimeSeconds: Int,
    val visibilityTimeoutSeconds: Int?,
    val errorVisibilityTimeoutSeconds: Int?,
    val messageVisibilityHeartbeatIntervalSeconds: Int?,
    val messageVisibilityHeartbeatSeconds: Int?,
    val autoStartup: Boolean,
    val phase: Int,
    val concurrency: Int,
    val stopTimeoutMillis: Long,
    val retry: SqsProperties.Retry,
    val batch: Boolean = false,
    val acknowledgementMode: SqsAcknowledgementMode = SqsAcknowledgementMode.INHERIT,
    val backPressureMode: SqsBackPressureMode = SqsBackPressureMode.FIXED,
    val maxInFlight: Int = maxMessages * concurrency,
    val fifoBatchGroupingStrategy: SqsFifoBatchGroupingStrategy =
        SqsFifoBatchGroupingStrategy.GROUP_BY_MESSAGE_GROUP_ID,
    val queueAttributeNames: Set<QueueAttributeName> = emptySet(),
    val queueAttributeCacheTtl: Duration = Duration.ofMinutes(1),
    val queueNotFoundStrategy: SqsQueueNotFoundStrategy = SqsQueueNotFoundStrategy.FAIL_FAST,
): Serializable {
    init {
        requireVisibilityHeartbeat(messageVisibilityHeartbeatIntervalSeconds, messageVisibilityHeartbeatSeconds)
        require(maxInFlight >= 1) { "maxInFlight must be greater than or equal to 1." }
        require(!queueAttributeCacheTtl.isNegative) { "queueAttributeCacheTtl must not be negative." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
