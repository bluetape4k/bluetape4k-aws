package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient

/**
 * Runtime holder for Ktor CloudWatch operations and plugin-owned client lifecycle.
 */
class CloudWatchKtorRuntime(
    val operations: CloudWatchKtorOperations,
    private val ownedClient: CloudWatchAsyncClient? = null,
) {

    companion object: KLoggingChannel()

    private val closed = atomic(false)

    /**
     * Closes the plugin-created CloudWatch client once. Injected clients are never closed.
     */
    suspend fun stop() {
        if (closed.compareAndSet(expect = false, update = true)) {
            ownedClient?.let { client ->
                runInterruptible(Dispatchers.IO) {
                    log.debug { "Closing CloudWatch client." }
                    client.close()
                }
            }
        }
    }
}
