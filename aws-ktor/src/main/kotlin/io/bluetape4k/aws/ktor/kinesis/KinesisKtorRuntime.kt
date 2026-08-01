package io.bluetape4k.aws.ktor.kinesis

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

/**
 * Kinesis Ktor 작업과 플러그인이 소유한 클라이언트 수명 주기를 보관하는 런타임입니다.
 */
class KinesisKtorRuntime(
    val operations: KinesisKtorOperations,
    private val ownedClient: KinesisAsyncClient? = null,
) {

    companion object: KLoggingChannel()

    private val closed = atomic(false)

    /**
     * 플러그인이 생성한 Kinesis 클라이언트를 한 번 닫습니다. 주입된 클라이언트는 닫지 않습니다.
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
