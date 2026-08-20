package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.context.SmartLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration

/** Extended Client admission drain을 Spring lifecycle과 연결합니다. */
class SqsExtendedClientLifecycle(
    private val client: SqsExtendedClient,
    private val properties: SqsExtendedClientProperties,
) : SmartLifecycle {

    @Volatile
    private var running: Boolean = true
    private val callbackDelivered = AtomicBoolean(false)
    private val stopLock = Any()

    override fun getPhase(): Int = PHASE

    override fun isAutoStartup(): Boolean = true

    override fun isRunning(): Boolean = running

    override fun start() {
        synchronized(stopLock) {
            running = true
        }
    }

    override fun stop() {
        stopInternal(null)
    }

    override fun stop(callback: Runnable) {
        stopInternal(callback)
    }

    private fun stopInternal(callback: Runnable?) {
        synchronized(stopLock) {
            if (!running) {
                deliverCallback(callback)
                return
            }
            runBlocking(Dispatchers.Default) {
                withContext(NonCancellable) {
                    client.stopForSpring(
                        timeout = properties.shutdownDrainTimeoutSeconds.toLong().seconds,
                        onDrained = {
                            running = false
                            deliverCallback(callback)
                        },
                        onTimeout = { active ->
                            client.recordLifecycleFailure(SqsExtendedDrainTimeoutException(active))
                        },
                    )
                }
            }
        }
    }

    private fun deliverCallback(callback: Runnable?) {
        if (callback != null && callbackDelivered.compareAndSet(false, true)) {
            callback.run()
        }
    }

    companion object {
        /** AWS client destroy보다 먼저 동작하는 명시적 lifecycle phase입니다. */
        const val PHASE: Int = Int.MAX_VALUE - 100
    }
}

/** Spring shutdown phase가 drain budget을 보장하는지 판정합니다. */
class SqsExtendedLifecycleBudgetCondition : SpringBootCondition() {

    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): ConditionOutcome {
        val drainSeconds = context.environment
            .getProperty("bluetape4k.aws.sqs.extended.shutdown-drain-timeout-seconds", Int::class.java, 20)
            .toLong()
        val phaseSeconds = parseSeconds(
            context.environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"),
        )
        val requiredSeconds = drainSeconds + PHASE_MARGIN_SECONDS
        return if (phaseSeconds >= requiredSeconds) {
            ConditionOutcome.match("shutdown phase budget is sufficient")
        } else {
            ConditionOutcome.noMatch(
                "shutdown phase budget must be at least ${requiredSeconds}s for extended drain",
            )
        }
    }

    private fun parseSeconds(raw: String?): Long {
        if (raw.isNullOrBlank()) return DEFAULT_PHASE_SECONDS
        return when {
            raw.startsWith("PT") -> JavaDuration.parse(raw).seconds
            raw.endsWith("ms") -> raw.removeSuffix("ms").toDouble().div(MILLIS_PER_SECOND).toLong()
            raw.endsWith("s") -> raw.removeSuffix("s").toDouble().toLong()
            raw.endsWith("m") -> raw.removeSuffix("m").toDouble().times(SECONDS_PER_MINUTE).toLong()
            else -> raw.toLongOrNull()?.div(MILLIS_PER_SECOND.toLong()) ?: DEFAULT_PHASE_SECONDS
        }
    }

    companion object {
        const val PHASE_MARGIN_SECONDS: Long = 5
        private const val DEFAULT_PHASE_SECONDS: Long = 30
        private const val MILLIS_PER_SECOND: Double = 1_000.0
        private const val SECONDS_PER_MINUTE: Double = 60.0
    }
}

/** Lifecycle bridge가 concrete extended client에만 붙도록 하는 선언용 annotation입니다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(SqsExtendedLifecycleBudgetCondition::class)
annotation class ConditionalOnSqsExtendedLifecycleBudget
