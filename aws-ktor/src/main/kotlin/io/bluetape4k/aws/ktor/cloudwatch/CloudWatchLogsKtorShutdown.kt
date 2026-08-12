package io.bluetape4k.aws.ktor.cloudwatch

import java.io.Serializable
import java.time.Duration

/**
 * 종료 시 CloudWatch Logs flush timeout을 처리하는 정책입니다.
 */
enum class CloudWatchLogsShutdownPolicy {
    /** timeout을 warning과 관찰 이벤트로 남기고 종료를 계속합니다. */
    WarnAndContinue,

    /** client를 닫고 pending event를 관찰한 뒤 timeout 예외를 전파합니다. */
    ThrowOnTimeout,
}

/**
 * 종료 flush 결과입니다.
 */
enum class CloudWatchLogsShutdownOutcome {
    /** 모든 buffered event를 정상적으로 flush했습니다. */
    Success,

    /** [CloudWatchLogsKtorRuntime.shutdownFlushTimeout] 안에 flush가 끝나지 않았습니다. */
    Timeout,

    /** flush가 예외로 종료되었습니다. */
    Failure,

    /** 호출자 coroutine이 취소되어 flush가 중단되었습니다. */
    Cancelled,
}

/**
 * CloudWatch Logs runtime 종료 flush를 관찰하기 위한 불변 이벤트입니다.
 *
 * [pendingEventCount]는 close 직전에 buffer에 남은 event 수입니다. 종료가 성공하지 않은
 * 경우 [droppedEventCount]는 client close 뒤 재시도되지 않는 event 수로 기록됩니다.
 */
data class CloudWatchLogsShutdownObservation(
    /** 종료 flush 결과입니다. */
    val outcome: CloudWatchLogsShutdownOutcome,
    /** client close 직전에 buffer에 남은 event 수입니다. */
    val pendingEventCount: Int,
    /** 종료 이후 재시도되지 않는 event 수입니다. */
    val droppedEventCount: Int,
    /** 실패 또는 취소를 유발한 원인입니다. 정상 종료와 timeout이 아닌 경우 설정될 수 있습니다. */
    val cause: Throwable? = null,
): Serializable {

    init {
        require(pendingEventCount >= 0) { "pendingEventCount must not be negative." }
        require(droppedEventCount >= 0) { "droppedEventCount must not be negative." }
        require(droppedEventCount <= pendingEventCount) {
            "droppedEventCount must not exceed pendingEventCount."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * CloudWatch Logs runtime 종료 관찰 이벤트를 전달하는 observer입니다.
 *
 * Observer 예외는 runtime 종료 결과를 덮어쓰지 않고 warning으로만 기록됩니다.
 */
fun interface CloudWatchLogsShutdownObserver {
    fun observe(observation: CloudWatchLogsShutdownObservation)
}

/**
 * [CloudWatchLogsShutdownPolicy.ThrowOnTimeout]에서 bounded flush timeout을 알리는 예외입니다.
 */
class CloudWatchLogsShutdownTimeoutException(
    /** 적용된 shutdown flush timeout입니다. */
    val timeout: Duration,
    /** client close 직전에 남은 pending event 수입니다. */
    val pendingEventCount: Int,
): IllegalStateException(
    "CloudWatch Logs shutdown flush timed out after $timeout with " +
        "$pendingEventCount pending event(s).",
) {

    init {
        require(!timeout.isNegative && !timeout.isZero) {
            "timeout must be positive."
        }
        require(pendingEventCount >= 0) { "pendingEventCount must not be negative." }
    }
}
