package io.bluetape4k.aws.ses.waiters

import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.waiters.SesWaiter

/**
 * Creates a [sesWaiter] instance with [SesWaiter.Builder].
 *
 * ```kotlin
 * val waiter = sesWaiter {
 *    client(sesClient)
 *    overrideConfiguration(waiterOverrideConfiguration)
 *    maxAttempts(10)
 *    delay(10)
 *    maxBackoffTime(1000)
 *    backoffStrategy(ExponentialBackoffStrategy())
 *    acceptors(...)
 *    customWaiterBuilder(...)
 *    customWaiterParameters(...)
 *    customWaiterConfiguration(...)
 * }
 * waiter.waitUntil(...)
 * ```
 *
 * @param builder [SesWaiter.Builder] initialization lambda.
 * @return [sesWaiter] instance.
 */
fun sesWaiter(
    builder: SesWaiter.Builder.() -> Unit,
): SesWaiter {
    return SesWaiter.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }
}

/**
 * Creates a [SesWaiter] instance.
 *
 * ```kotlin
 * val waiter = sesWaiterOf(sesClient)
 * waiter.waitUntil(...)
 * ```
 *
 * @param client [SesClient] instance.
 * @param configuration [WaiterOverrideConfiguration] instance.
 * @return [SesWaiter] instance.
 */
fun sesWaiterOf(
    client: SesClient,
    configuration: WaiterOverrideConfiguration = waiterOverrideConfigurationOf(),
    builder: SesWaiter.Builder.() -> Unit = {},
): SesWaiter = sesWaiter {
    client(client)
    overrideConfiguration(configuration)
    builder()
}
