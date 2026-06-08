package io.bluetape4k.aws.ktor.cloudwatch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime holder for Ktor CloudWatch operations and plugin-owned client lifecycle.
 */
class CloudWatchKtorRuntime(
    val operations: CloudWatchKtorOperations,
    private val ownedClient: CloudWatchAsyncClient? = null,
) {

    private val closed = AtomicBoolean(false)

    /**
     * Closes the plugin-created CloudWatch client once. Injected clients are never closed.
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
