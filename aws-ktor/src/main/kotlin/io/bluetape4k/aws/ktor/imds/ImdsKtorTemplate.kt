package io.bluetape4k.aws.ktor.imds

import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import java.time.Duration

/**
 * Coroutine-friendly [ImdsKtorOperations] backed by AWS SDK v2 [Ec2MetadataAsyncClient].
 */
class ImdsKtorTemplate(
    private val ec2MetadataAsyncClient: Ec2MetadataAsyncClient,
    private val requestTimeout: Duration = Duration.ofSeconds(1),
): ImdsKtorOperations {

    init {
        require(!requestTimeout.isNegative && !requestTimeout.isZero) {
            "requestTimeout must be positive."
        }
    }

    override suspend fun get(path: String): String {
        val normalizedPath = normalizePath(path)
        return withTimeout(requestTimeout.toMillis()) {
            ec2MetadataAsyncClient.get(normalizedPath).await().asString()
        }
    }

    override suspend fun getList(path: String): List<String> {
        val normalizedPath = normalizePath(path)
        return withTimeout(requestTimeout.toMillis()) {
            ec2MetadataAsyncClient.get(normalizedPath).await().asList()
        }
    }

    private fun normalizePath(path: String): String {
        path.requireNotBlank("path")
        return if (path.startsWith("/")) path else "/$path"
    }
}

