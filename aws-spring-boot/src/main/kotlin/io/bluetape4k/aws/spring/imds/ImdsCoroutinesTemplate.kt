package io.bluetape4k.aws.spring.imds

import io.bluetape4k.coroutines.support.awaitUntil
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import kotlin.time.toKotlinDuration

/**
 * AWS SDK v2 [Ec2MetadataAsyncClient]를 사용하는 코루틴 친화적인 [ImdsOperations]입니다.
 */
class ImdsCoroutinesTemplate(
    private val ec2MetadataAsyncClient: Ec2MetadataAsyncClient,
    private val properties: ImdsProperties,
): ImdsOperations {

    override suspend fun get(path: String): String {
        val normalizedPath = normalizePath(path)

        return ec2MetadataAsyncClient.get(normalizedPath)
            .awaitUntil(properties.requestTimeout.toKotlinDuration())
            .asString()
    }

    override suspend fun getList(path: String): List<String> {
        val normalizedPath = normalizePath(path)

        return ec2MetadataAsyncClient.get(normalizedPath)
            .awaitUntil(properties.requestTimeout.toKotlinDuration())
            .asList()
    }

    private fun normalizePath(path: String): String {
        path.requireNotBlank("path")
        return if (path.startsWith("/")) path else "/$path"
    }
}
