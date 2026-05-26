package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import java.io.Serializable
import java.time.Duration
import kotlin.math.roundToInt
import kotlin.random.Random
import software.amazon.awssdk.services.sqs.model.Message

/**
 * Policy applied when message conversion fails before the handler is invoked.
 */
enum class SqsConversionFailurePolicy {
    /**
     * Apply the same failure path as handler exceptions.
     */
    HandleAsFailure,

    /**
     * Delete the source message after conversion failure.
     */
    Delete,

    /**
     * Leave the message untouched so SQS redelivers it after visibility expires.
     */
    Ignore,
}

/**
 * Runtime phase where a message failure was observed.
 */
enum class SqsConsumerFailurePhase {
    Conversion,
    Handler,
}

/**
 * Failure context used by retry visibility strategies and observers.
 */
data class SqsConsumerFailureContext(
    val queueUrl: String,
    val message: Message,
    val cause: Throwable,
    val phase: SqsConsumerFailurePhase,
): Serializable {

    val approximateReceiveCount: Int?
        get() = message.attributesAsStrings()["ApproximateReceiveCount"]?.toIntOrNull()

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Strategy that chooses visibility timeout after conversion or handler failure.
 */
fun interface SqsFailureVisibilityStrategy {
    fun visibilityTimeoutSeconds(context: SqsConsumerFailureContext): Int?
}

/**
 * Fixed failure visibility timeout strategy.
 */
data class SqsFixedFailureVisibilityStrategy(
    val timeoutSeconds: Int,
): SqsFailureVisibilityStrategy, Serializable {

    init {
        timeoutSeconds.requireInRange(0, 43_200, "timeoutSeconds")
    }

    override fun visibilityTimeoutSeconds(context: SqsConsumerFailureContext): Int =
        timeoutSeconds

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Linear receive-count based retry visibility strategy with optional jitter.
 *
 * Contract:
 * - Uses the SQS `ApproximateReceiveCount` system attribute when present.
 * - Returns a timeout between `0` and [maxTimeoutSeconds].
 * - [jitterRatio] widens the computed timeout by a random +/- percentage.
 */
class SqsLinearFailureVisibilityStrategy(
    val baseTimeoutSeconds: Int,
    val maxTimeoutSeconds: Int,
    val jitterRatio: Double = 0.0,
    private val random: Random = Random.Default,
): SqsFailureVisibilityStrategy, Serializable {

    init {
        baseTimeoutSeconds.requireInRange(0, 43_200, "baseTimeoutSeconds")
        maxTimeoutSeconds.requireInRange(0, 43_200, "maxTimeoutSeconds")
        maxTimeoutSeconds.requireGe(baseTimeoutSeconds, "maxTimeoutSeconds")
        jitterRatio.requireInRange(0.0, 1.0, "jitterRatio")
    }

    override fun visibilityTimeoutSeconds(context: SqsConsumerFailureContext): Int {
        val attempt = context.approximateReceiveCount?.coerceAtLeast(1) ?: 1
        val base = (baseTimeoutSeconds.toLong() * attempt.toLong())
            .coerceAtMost(maxTimeoutSeconds.toLong())
            .toInt()
        if (base == 0 || jitterRatio == 0.0) {
            return base
        }

        val spread = (base * jitterRatio).roundToInt().coerceAtLeast(1)
        return (base + random.nextInt(-spread, spread + 1)).coerceIn(0, maxTimeoutSeconds)
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Interceptor for SQS receive, handler invocation, ack, and nack lifecycle hooks.
 */
interface SqsConsumerInterceptor {
    suspend fun beforeReceive(queueUrl: String) {}
    suspend fun afterReceive(queueUrl: String, messages: List<Message>) {}
    suspend fun receiveFailed(queueUrl: String?, cause: Throwable, retryDelay: Duration) {}
    suspend fun beforeInvoke(context: SqsMessageContext) {}
    suspend fun afterInvoke(context: SqsMessageContext) {}
    suspend fun invokeFailed(context: SqsMessageContext, cause: Throwable) {}
    suspend fun beforeAck(context: SqsMessageContext) {}
    suspend fun afterAck(context: SqsMessageContext) {}
    suspend fun beforeNack(context: SqsMessageContext, timeoutSeconds: Int) {}
    suspend fun afterNack(context: SqsMessageContext, timeoutSeconds: Int) {}
}

/**
 * Lightweight observation event that can be bridged to Micrometer, OpenTelemetry,
 * logs, or test probes without forcing a metrics runtime dependency.
 */
data class SqsConsumerObservation(
    val operation: String,
    val outcome: String,
    val queueUrl: String? = null,
    val messageId: String? = null,
    val duration: Duration? = null,
    val tags: Map<String, String> = emptyMap(),
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Observer for SQS runtime operations.
 */
fun interface SqsConsumerObserver {
    fun observe(observation: SqsConsumerObservation)
}
