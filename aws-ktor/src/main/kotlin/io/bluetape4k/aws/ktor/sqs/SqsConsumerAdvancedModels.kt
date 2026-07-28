package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import java.io.Serializable
import java.time.Duration
import kotlin.math.roundToInt
import kotlin.random.Random
import software.amazon.awssdk.services.sqs.model.Message

/**
 * handler 호출 전에 메시지 변환이 실패했을 때 적용할 정책입니다.
 */
enum class SqsConversionFailurePolicy {
    /**
     * handler 예외와 동일한 실패 경로를 적용합니다.
     */
    HandleAsFailure,

    /**
     * 변환 실패 후 원본 메시지를 삭제합니다.
     */
    Delete,

    /**
     * 메시지를 변경하지 않아 visibility 만료 후 SQS가 다시 전달하게 둡니다.
     */
    Ignore,
}

/**
 * 메시지 실패가 관측된 runtime 단계입니다.
 */
enum class SqsConsumerFailurePhase {
    Conversion,
    Handler,
}

/**
 * retry visibility 전략과 observer가 사용하는 실패 context입니다.
 */
data class SqsConsumerFailureContext(
    /** 실패가 발생한 원본 queue URL입니다. */
    val queueUrl: String,
    /** 실패한 AWS SDK SQS 메시지 원본입니다. */
    val message: Message,
    /** 변환 또는 handler 실행 중 발생한 원인 예외입니다. */
    val cause: Throwable,
    /** 실패가 발생한 runtime 단계입니다. */
    val phase: SqsConsumerFailurePhase,
): Serializable {

    /**
     * SQS `ApproximateReceiveCount` system attribute에서 읽은 대략적인 수신 횟수입니다.
     *
     * attribute가 없거나 정수로 해석할 수 없으면 `null`을 반환합니다.
     */
    val approximateReceiveCount: Int?
        get() = message.attributesAsStrings()["ApproximateReceiveCount"]?.toIntOrNull()

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 변환 또는 handler 실패 후 적용할 visibility timeout을 선택하는 전략입니다.
 */
fun interface SqsFailureVisibilityStrategy {
    fun visibilityTimeoutSeconds(context: SqsConsumerFailureContext): Int?
}

/**
 * 고정된 실패 visibility timeout을 반환하는 전략입니다.
 */
data class SqsFixedFailureVisibilityStrategy(
    /** 실패 후 적용할 visibility timeout 초 단위 값입니다. `0..43_200` 범위여야 합니다. */
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
 * 수신 횟수에 비례해 증가하는 retry visibility 전략입니다.
 *
 * 계약:
 * - 가능하면 SQS `ApproximateReceiveCount` system attribute를 사용합니다.
 * - `0`과 [maxTimeoutSeconds] 사이의 timeout을 반환합니다.
 * - [jitterRatio]는 계산된 timeout에 무작위 +/- 비율을 적용합니다.
 */
class SqsLinearFailureVisibilityStrategy(
    /** 수신 횟수 1회 기준으로 적용할 기본 visibility timeout 초 단위 값입니다. */
    val baseTimeoutSeconds: Int,
    /** 계산된 timeout의 상한 초 단위 값입니다. */
    val maxTimeoutSeconds: Int,
    /** 계산된 timeout에 적용할 jitter 비율입니다. `0.0..1.0` 범위여야 합니다. */
    val jitterRatio: Double = 0.0,
    /** jitter 계산에 사용할 난수 생성기입니다. 테스트에서는 deterministic generator를 주입할 수 있습니다. */
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
 * SQS receive, handler invocation, ack, nack lifecycle hook을 가로채는 interceptor입니다.
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
 * metrics runtime 의존성을 강제하지 않고 Micrometer, OpenTelemetry, log, test probe로
 * 전달할 수 있는 경량 관측 이벤트입니다.
 */
data class SqsConsumerObservation(
    /** 관측된 runtime operation 이름입니다. */
    val operation: String,
    /** operation 결과입니다. 보통 `success` 또는 `failure`입니다. */
    val outcome: String,
    /** operation이 대상으로 삼은 queue URL입니다. queue를 아직 확인하지 못했으면 `null`일 수 있습니다. */
    val queueUrl: String? = null,
    /** operation이 대상으로 삼은 SQS message id입니다. 메시지 단위 이벤트가 아니면 `null`일 수 있습니다. */
    val messageId: String? = null,
    /** operation 수행 시간입니다. 시작 시간을 알 수 없는 이벤트에서는 `null`일 수 있습니다. */
    val duration: Duration? = null,
    /** 관측 backend로 전달할 추가 tag입니다. 예외 class, message count, retry delay 등을 담습니다. */
    val tags: Map<String, String> = emptyMap(),
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SQS runtime operation을 관측하는 observer입니다.
 */
fun interface SqsConsumerObserver {
    fun observe(observation: SqsConsumerObservation)
}
