package io.bluetape4k.aws.ktor.s3vectors

import io.bluetape4k.aws.s3vectors.S3VectorsOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime holder for Ktor S3 Vectors operations and plugin-owned client lifecycle.
 */
class S3VectorsKtorRuntime(
    val operations: S3VectorsOperations,
    private val ownedClient: S3VectorsAsyncClient? = null,
) {

    private val closed = AtomicBoolean(false)

    /**
     * Closes the plugin-created S3 Vectors client once. Injected clients are never closed.
     */
    suspend fun stop() {
        if (closed.compareAndSet(false, true)) {
            ownedClient?.let { client ->
                runInterruptible(Dispatchers.IO) {
                    client.close()
                }
            }
        }
    }
}
