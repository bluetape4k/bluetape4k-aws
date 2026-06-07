package io.bluetape4k.aws.ktor.imds

import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime holder for Ktor IMDS operations and plugin-owned client lifecycle.
 */
class ImdsKtorRuntime(
    val operations: ImdsKtorOperations,
    private val ownedClient: Ec2MetadataAsyncClient? = null,
) {

    private val closed = AtomicBoolean(false)

    /**
     * Closes the plugin-created IMDS client once. Injected clients are never closed.
     */
    fun stop() {
        if (closed.compareAndSet(false, true)) {
            ownedClient?.close()
        }
    }
}

