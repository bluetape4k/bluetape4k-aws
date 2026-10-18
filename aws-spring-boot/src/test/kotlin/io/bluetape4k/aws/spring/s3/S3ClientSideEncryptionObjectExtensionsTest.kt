package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.PutObjectResponse

class S3ClientSideEncryptionObjectExtensionsTest: AbstractS3Test() {

    companion object: KLogging()

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `typed encrypted extensions reuse converter`() = runTest {
        val converter = RecordingObjectConverter()
        val operations = FakeEncryptedOperations(byteArrayOf(4, 5, 6))
        val value = mapOf("name" to "bluetape")

        operations.uploadEncryptedObject("bucket", "object", value, converter)
        operations.downloadEncryptedObject(
            "bucket",
            "object",
            Map::class.java as Class<Map<String, String>>,
            converter,
        ) shouldBeEqualTo value

        converter.writes shouldBeEqualTo 1
        converter.reads shouldBeEqualTo 1
    }
}

private class RecordingObjectConverter: S3ObjectConverter<Map<String, String>> {
    var writes: Int = 0
    var reads: Int = 0

    override val contentType: String = "application/test"

    override fun write(value: Map<String, String>): ByteArray {
        writes++
        return value.entries.joinToString("=").encodeToByteArray()
    }

    override fun read(bytes: ByteArray, targetType: Class<out Map<String, String>>): Map<String, String> {
        reads++
        return mapOf("name" to "bluetape")
    }
}

private class FakeEncryptedOperations(
    private val plaintext: ByteArray,
): S3ClientSideEncryptionOperations {
    override suspend fun uploadEncrypted(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String?,
        metadata: Map<String, String>,
        encryptionContext: Map<String, String>,
    ): PutObjectResponse = PutObjectResponse.builder().build()

    override suspend fun downloadEncryptedBytes(
        bucket: String,
        key: String,
        encryptionContext: Map<String, String>,
    ): ByteArray = plaintext.copyOf()
}
