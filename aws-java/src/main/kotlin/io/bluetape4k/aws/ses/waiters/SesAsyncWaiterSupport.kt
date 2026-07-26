package io.bluetape4k.aws.ses.waiters

import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration
import software.amazon.awssdk.services.ses.SesAsyncClient
import software.amazon.awssdk.services.ses.waiters.SesAsyncWaiter
import java.util.concurrent.ScheduledExecutorService

/**
 * Creates a [sesAsyncWaiter] instance with [SesAsyncWaiter.Builder].
 *
 * ```kotlin
 * val waiter = sesAsyncWaiter {
 *    client(sesAsyncClient)
 *    scheduledExecutorService(scheduledExecutorService)
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
 * @param builder [SesAsyncWaiter.Builder] initialization lambda.
 * @return [sesAsyncWaiter] instance.
 */
fun sesAsyncWaiter(
    builder: SesAsyncWaiter.Builder.() -> Unit,
): SesAsyncWaiter {
    return SesAsyncWaiter.builder().apply(builder).build()
        .apply {
            ShutdownQueue.register(this)
        }
}

/**
 * Creates a [SesAsyncWaiter] instance.
 *
 * ```kotlin
 * val waiter = sesAsyncWaiterOf(sesAsyncClient, scheduledExecutorService)
 * waiter.waitUntil(...)
 * ```
 *
 * @param client [SesAsyncClient] instance.
 * @param scheduledExecutorService [ScheduledExecutorService] instance.
 * @param configuration [WaiterOverrideConfiguration] instance.
 * @return [SesAsyncWaiter] instance.
 */
fun sesAsyncWaiterOf(
    client: SesAsyncClient,
    scheduledExecutorService: ScheduledExecutorService,
    configuration: WaiterOverrideConfiguration = waiterOverrideConfigurationOf(),
    builder: SesAsyncWaiter.Builder.() -> Unit = {},
): SesAsyncWaiter = sesAsyncWaiter {
    client(client)
    scheduledExecutorService(scheduledExecutorService)
    overrideConfiguration(configuration)

    builder()
}
