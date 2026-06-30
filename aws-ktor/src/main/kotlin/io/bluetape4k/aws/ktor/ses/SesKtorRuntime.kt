package io.bluetape4k.aws.ktor.ses

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient

/**
 * Runtime holder for SES Ktor operations and plugin-owned client lifecycle.
 */
class SesKtorRuntime(
    val operations: SesKtorOperations,
    private val ownedClient: SesV2AsyncClient? = null,
) {

    companion object: KLoggingChannel()

    private val closed = atomic(false)

    /**
     * Closes the plugin-created SES client once. Injected clients are never closed.
     */
    suspend fun stop() {
        if (closed.compareAndSet(expect = false, update = true)) {
            ownedClient?.let { client ->
                runInterruptible(Dispatchers.IO) {
                    log.debug { "Closing SES v2 client." }
                    client.close()
                }
            }
        }
    }
}
