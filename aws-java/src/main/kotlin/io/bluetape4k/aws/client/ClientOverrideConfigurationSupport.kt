package io.bluetape4k.aws.client

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration

/**
 * Creates [ClientOverrideConfiguration] with a builder DSL.
 *
 * ## Behavior and contract
 * - Applies [builder] to the builder created by [ClientOverrideConfiguration.builder].
 * - Freezes the configured result with `build()` and returns it.
 *
 * ```kotlin
 * val configuration = clientOverrideConfiguration {
 *     apiCallAttemptTimeout(java.time.Duration.ofSeconds(1))
 * }
 * // configuration.apiCallAttemptTimeout().isPresent == true
 * ```
 */
inline fun clientOverrideConfiguration(
    builder: ClientOverrideConfiguration.Builder.() -> Unit,
): ClientOverrideConfiguration {
    return ClientOverrideConfiguration.builder().apply(builder).build()
}
