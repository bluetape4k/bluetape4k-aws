package io.bluetape4k.aws.ktor.imds

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * AWS SDK v2 [Ec2MetadataAsyncClient]를 사용하는 코루틴 친화적인 [ImdsKtorOperations]입니다.
 */
class ImdsKtorTemplate(
    private val ec2MetadataAsyncClient: Ec2MetadataAsyncClient,
    private val requestTimeout: Duration = 1.seconds(),
): ImdsKtorOperations {

    init {
        requestTimeout.requireGt(Duration.ZERO, "requestTimeout")
    }

    override suspend fun get(path: String): String {
        val normalizedPath = normalizePath(path)
        return withTimeout(requestTimeout.toKotlinDuration()) {
            ec2MetadataAsyncClient.get(normalizedPath).await().asString()
        }
    }

    override suspend fun getList(path: String): List<String> {
        val normalizedPath = normalizePath(path)
        return withTimeout(requestTimeout.toKotlinDuration()) {
            ec2MetadataAsyncClient.get(normalizedPath).await().asList()
        }
    }

    private fun normalizePath(path: String): String {
        path.requireNotBlank("path")
        return if (path.startsWith("/")) path else "/$path"
    }

    override fun toString(): String =
        ToStringBuilder(this)
            .add("ec2MetadataAsyncClient", ec2MetadataAsyncClient)
            .add("requestTimeout", requestTimeout)
            .toString()
}
