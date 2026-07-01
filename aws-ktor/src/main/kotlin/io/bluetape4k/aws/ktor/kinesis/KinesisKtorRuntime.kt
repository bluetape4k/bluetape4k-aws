package io.bluetape4k.aws.ktor.kinesis

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

/**
 * Runtime holder for Kinesis Ktor operations and plugin-owned client lifecycle.
 */
class KinesisKtorRuntime(
    val operations: KinesisKtorOperations,
    private val ownedClient: KinesisAsyncClient? = null,
) {

    companion object: KLoggingChannel()

    private val closed = atomic(false)

    /**
     * Closes the plugin-created Kinesis client once. Injected clients are never closed.
     */
    suspend fun stop() {
        if (closed.compareAndSet(expect = false, update = true)) {
            ownedClient?.let { client ->
                runInterruptible(Dispatchers.IO) {
                    log.debug { "Closing Kinesis client." }
                    client.close()
                }
            }
        }
    }
}
