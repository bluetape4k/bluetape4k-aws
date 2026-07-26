package io.bluetape4k.aws.client

import software.amazon.awssdk.core.client.config.ClientAsyncConfiguration
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption

/**
 * Creates [ClientAsyncConfiguration] with a builder DSL.
 *
 * ## Behavior and contract
 * - Applies [builder] to the builder created by [ClientAsyncConfiguration.builder].
 * - Returns the `build()` result after applying [builder].
 *
 * ```kotlin
 * val config = clientAsyncConfiguration {
 *     advancedOption(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, java.util.concurrent.Executors.newSingleThreadExecutor())
 * }
 * // config.advancedOption(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR) != null
 * ```
 */
inline fun clientAsyncConfiguration(
    builder: ClientAsyncConfiguration.Builder.() -> Unit,
): ClientAsyncConfiguration {
    return ClientAsyncConfiguration.builder().apply(builder).build()
}

/**
 * Creates [ClientAsyncConfiguration] with a single advanced async option.
 *
 * ## Behavior and contract
 * - Calls `advancedOption(asyncOption, value)` once inside the [clientAsyncConfiguration] block.
 * - The supplied option and value are reflected directly in the resulting configuration.
 *
 * ```kotlin
 * val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
 * val config = clientAsyncConfigurationOf(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, executor)
 * // config.advancedOption(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR) == executor
 * ```
 */
fun <T> clientAsyncConfigurationOf(
    asyncOption: SdkAdvancedAsyncClientOption<T>,
    value: T,
): ClientAsyncConfiguration = clientAsyncConfiguration {
    advancedOption(asyncOption, value)
}
