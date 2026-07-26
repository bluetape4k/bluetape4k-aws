package io.bluetape4k.aws.ses.waiters

import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration
import software.amazon.awssdk.retries.api.BackoffStrategy
import java.time.Duration

/**
 * Builds [WaiterOverrideConfiguration] with a DSL block.
 *
 * ```kotlin
 * val cfg = waiterOverrideConfiguration {
 *     maxAttempts(5)
 *     waitTimeout(Duration.ofSeconds(10))
 * }
 * ```
 */
fun waiterOverrideConfiguration(
    builder: WaiterOverrideConfiguration.Builder.() -> Unit,
): WaiterOverrideConfiguration =
    WaiterOverrideConfiguration.builder().apply(builder).build()

/**
 * Creates [WaiterOverrideConfiguration] with defaults.
 *
 * ## Behavior and contract
 * - [maxAttempts] defaults to 3 and [waitTimeout] defaults to 5 seconds.
 * - [backoffStrategy] defaults to a fixed 10 ms delay.
 *
 * ```kotlin
 * val cfg = waiterOverrideConfigurationOf(maxAttempts = 5, waitTimeout = Duration.ofSeconds(10))
 * // cfg.maxAttempts().get() == 5
 * ```
 */
fun waiterOverrideConfigurationOf(
    maxAttempts: Int = 3,
    waitTimeout: Duration = Duration.ofSeconds(5),
    backoffStrategy: BackoffStrategy = BackoffStrategy.fixedDelay(Duration.ofMillis(10)),
): WaiterOverrideConfiguration = waiterOverrideConfiguration {
    this.backoffStrategyV2(backoffStrategy)
    this.maxAttempts(maxAttempts)
    this.waitTimeout(waitTimeout)
}
