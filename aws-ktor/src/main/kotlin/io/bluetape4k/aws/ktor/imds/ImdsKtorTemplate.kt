package io.bluetape4k.aws.ktor.imds

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.withTimeout
import java.time.Duration
import kotlin.time.toKotlinDuration

/** Smithy HTTP 기반 IMDSv2 요청을 제공하는 코루틴 친화적인 작업 구현입니다. */
internal class ImdsKtorTemplate(
    private val adapter: ImdsSmithyAdapter,
    private val requestTimeout: Duration = 1.seconds(),
) : ImdsKtorOperations {

    init {
        requestTimeout.requireGt(Duration.ZERO, "requestTimeout")
    }

    override suspend fun get(path: String): String {
        val normalizedPath = normalizePath(path)
        return withTimeout(requestTimeout.toKotlinDuration()) {
            adapter.get(normalizedPath)
        }
    }

    override suspend fun getList(path: String): List<String> {
        val normalizedPath = normalizePath(path)
        return withTimeout(requestTimeout.toKotlinDuration()) {
            adapter.getList(normalizedPath)
        }
    }

    private fun normalizePath(path: String): String {
        path.requireNotBlank("path")
        require(!path.startsWith("//") && !path.contains("://")) {
            "path must be a metadata path, not an absolute URL."
        }
        return if (path.startsWith('/')) path else "/$path"
    }

    override fun toString(): String =
        ToStringBuilder(this)
            .add("adapterConfigured", true)
            .add("requestTimeout", requestTimeout)
            .toString()
}
