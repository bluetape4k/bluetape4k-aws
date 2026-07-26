package io.bluetape4k.aws.core

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration

/**
 * Creates [AwsRequestOverrideConfiguration] with a builder DSL.
 *
 * ## Behavior and contract
 * - Creates a new builder with [AwsRequestOverrideConfiguration.builder], then applies [builder].
 * - Returns the `build()` result immediately after [builder] runs.
 *
 * ```kotlin
 * val configuration = awsRequestOverrideConfiguration {
 *     apiCallTimeout(java.time.Duration.ofSeconds(1))
 * }
 * // configuration.apiCallTimeout().isPresent == true
 * ```
 */
inline fun awsRequestOverrideConfiguration(
    builder: AwsRequestOverrideConfiguration.Builder.() -> Unit,
): AwsRequestOverrideConfiguration {
    return AwsRequestOverrideConfiguration.builder().apply(builder).build()
}

/**
 * Creates [AwsRequestOverrideConfiguration] with a request-level credentials provider.
 *
 * ## Behavior and contract
 * - Calls `credentialsProvider(credentialsProvider)` inside [awsRequestOverrideConfiguration].
 * - The returned object includes the supplied provider as request override credentials.
 *
 * ```kotlin
 * val provider = software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.create()
 * val configuration = awsRequestOverrideConfigurationOf(provider)
 * // configuration.credentialsProvider().orElse(null) == provider
 * ```
 */
fun awsRequestOverrideConfigurationOf(
    credentialsProvider: AwsCredentialsProvider,
): AwsRequestOverrideConfiguration {
    return awsRequestOverrideConfiguration {
        credentialsProvider(credentialsProvider)
    }
}
