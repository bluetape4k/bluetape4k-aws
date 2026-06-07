package io.bluetape4k.aws.spring.imds

import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient

/**
 * Coroutine-friendly [ImdsOperations] backed by AWS SDK v2 [Ec2MetadataAsyncClient].
 */
class ImdsCoroutinesTemplate(
    private val ec2MetadataAsyncClient: Ec2MetadataAsyncClient,
    private val properties: ImdsProperties,
): ImdsOperations {

    override suspend fun get(path: String): String {
        val normalizedPath = normalizePath(path)
        return withTimeout(properties.requestTimeout.toMillis()) {
            ec2MetadataAsyncClient.get(normalizedPath).await().asString()
        }
    }

    override suspend fun getList(path: String): List<String> {
        val normalizedPath = normalizePath(path)
        return withTimeout(properties.requestTimeout.toMillis()) {
            ec2MetadataAsyncClient.get(normalizedPath).await().asList()
        }
    }

    private fun normalizePath(path: String): String {
        path.requireNotBlank("path")
        return if (path.startsWith("/")) path else "/$path"
    }
}
