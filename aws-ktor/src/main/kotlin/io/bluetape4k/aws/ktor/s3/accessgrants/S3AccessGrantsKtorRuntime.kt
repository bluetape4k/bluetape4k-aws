package io.bluetape4k.aws.ktor.s3.accessgrants

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.s3control.S3ControlAsyncClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime holder for Ktor S3 Access Grants operations and plugin-owned client lifecycle.
 */
class S3AccessGrantsKtorRuntime(
    val operations: S3AccessGrantsKtorOperations,
    private val ownedClient: S3ControlAsyncClient? = null,
) {

    private val closed = AtomicBoolean(false)

    /**
     * Closes the plugin-created S3 Control client once. Injected clients are never closed.
     */
    suspend fun stop() {
        if (closed.compareAndSet(false, true)) {
            ownedClient?.let { client ->
                withContext(Dispatchers.IO) {
                    runInterruptible {
                        client.close()
                    }
                }
            }
        }
    }
}
