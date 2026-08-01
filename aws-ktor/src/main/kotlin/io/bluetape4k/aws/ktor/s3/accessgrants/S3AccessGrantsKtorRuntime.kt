package io.bluetape4k.aws.ktor.s3.accessgrants

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.services.s3control.S3ControlAsyncClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ktor S3 Access Grants 작업과 플러그인이 소유한 클라이언트 수명 주기를 보관하는 런타임입니다.
 */
class S3AccessGrantsKtorRuntime(
    val operations: S3AccessGrantsKtorOperations,
    private val ownedClient: S3ControlAsyncClient? = null,
) {

    private val closed = AtomicBoolean(false)

    /**
     * 플러그인이 생성한 S3 Control 클라이언트를 한 번 닫습니다. 주입된 클라이언트는 닫지 않습니다.
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
