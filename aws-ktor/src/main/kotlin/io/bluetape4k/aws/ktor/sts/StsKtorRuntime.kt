package io.bluetape4k.aws.ktor.sts

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.services.sts.StsAsyncClient

/**
 * Runtime holder for STS Ktor operations and plugin-owned client lifecycle.
 */
class StsKtorRuntime(
    val operations: StsKtorOperations,
    private val ownedClient: StsAsyncClient? = null,
) {

    companion object: KLoggingChannel()

    private val closed = atomic(false)

    /**
     * Closes the plugin-created STS client once. Injected clients are never closed.
     */
    suspend fun stop() {
        if (closed.compareAndSet(expect = false, update = true)) {
            ownedClient?.let { client ->
                runInterruptible(Dispatchers.IO) {
                    log.debug { "Closing STS client." }
                    client.close()
                }
            }
        }
    }
}
