package io.bluetape4k.aws.ktor.sns

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.services.sns.SnsAsyncClient

/**
 * Runtime holder for SNS Ktor operations and plugin-owned client lifecycle.
 */
class SnsKtorRuntime(
    val operations: SnsKtorOperations,
    val parser: SnsHttpMessageParser = SnsHttpMessageParser.default(),
    private val ownedClient: SnsAsyncClient? = null,
) {

    companion object: KLoggingChannel()

    private val closed = atomic(false)

    /**
     * Closes the plugin-created SNS client once. Injected clients are never closed.
     */
    suspend fun stop() {
        if (closed.compareAndSet(expect = false, update = true)) {
            ownedClient?.let { client ->
                runInterruptible(Dispatchers.IO) {
                    log.debug { "Closing SNS client." }
                    client.close()
                }
            }
        }
    }
}
