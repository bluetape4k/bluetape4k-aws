package io.bluetape4k.aws.ktor.eventbridge

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient

/**
 * Runtime holder for EventBridge Ktor operations and plugin-owned client lifecycle.
 */
class EventBridgeKtorRuntime(
    val operations: EventBridgeKtorOperations,
    private val ownedClient: EventBridgeAsyncClient? = null,
) {

    companion object: KLoggingChannel()

    private val closed = atomic(false)

    /**
     * Closes the plugin-created EventBridge client once. Injected clients are never closed.
     */
    suspend fun stop() {
        if (closed.compareAndSet(expect = false, update = true)) {
            ownedClient?.let { client ->
                runInterruptible(Dispatchers.IO) {
                    log.debug { "Closing EventBridge client." }
                    client.close()
                }
            }
        }
    }
}
