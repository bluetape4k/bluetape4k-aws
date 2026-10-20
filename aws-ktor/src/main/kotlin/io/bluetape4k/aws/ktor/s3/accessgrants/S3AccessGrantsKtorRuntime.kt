package io.bluetape4k.aws.ktor.s3.accessgrants

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.ktor.core.ApplicationResourceRegistry
import aws.sdk.kotlin.services.s3control.S3ControlClient
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

/**
 * Ktor S3 Access Grants 작업과 플러그인이 소유한 클라이언트 수명 주기를 보관하는 런타임입니다.
 */
class S3AccessGrantsKtorRuntime(
    val operations: S3AccessGrantsKtorOperations,
    private val ownedClient: S3ControlClient? = null,
) {

    private val closed = atomic(false)
    private val resourceRegistrationInstalled = atomic(false)
    private val ownedClientResource = AutoCloseable {
        runBlocking(Dispatchers.IO) {
            stop()
        }
    }

    /** 플러그인이 소유한 클라이언트를 공통 애플리케이션 lifecycle registry에 등록합니다. */
    internal fun registerApplicationResources(registry: ApplicationResourceRegistry) {
        if (ownedClient != null && resourceRegistrationInstalled.compareAndSet(expect = false, update = true)) {
            registry.register(ownedClientResource)
        }
    }

    /**
     * 플러그인이 생성한 S3 Control 클라이언트를 한 번 닫습니다. 주입된 클라이언트는 닫지 않습니다.
     */
    suspend fun stop() {
        if (closed.compareAndSet(expect = false, update = true)) {
            ownedClient?.let { client ->
                runInterruptible(Dispatchers.IO) {
                    client.close()
                }
            }
        }
    }

    override fun toString(): String =
        ToStringBuilder(this)
            .add("operationsConfigured", true)
            .add("ownedClientConfigured", ownedClient != null)
            .add("closed", closed.value)
            .toString()
}
