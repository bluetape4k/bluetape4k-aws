package io.bluetape4k.aws.ktor.imds

import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ktor IMDS 작업과 플러그인이 소유한 클라이언트 수명 주기를 보관하는 런타임입니다.
 */
class ImdsKtorRuntime(
    val operations: ImdsKtorOperations,
    private val ownedClient: Ec2MetadataAsyncClient? = null,
) {

    private val closed = AtomicBoolean(false)

    /**
     * 플러그인이 생성한 IMDS 클라이언트를 한 번 닫습니다. 주입된 클라이언트는 닫지 않습니다.
     */
    fun stop() {
        if (closed.compareAndSet(false, true)) {
            ownedClient?.close()
        }
    }
}
