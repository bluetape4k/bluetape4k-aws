package io.bluetape4k.aws.examples.ktor.s3

import io.bluetape4k.aws.ktor.s3.S3KtorAddressingStyle
import io.bluetape4k.aws.ktor.s3.S3KtorClient
import io.bluetape4k.aws.ktor.s3.S3KtorClientSideEncryption
import io.bluetape4k.aws.ktor.s3.S3KtorConfigObject
import io.bluetape4k.aws.ktor.s3.S3KtorDataKey
import io.bluetape4k.aws.ktor.s3.S3KtorDataKeyProvider
import io.bluetape4k.aws.ktor.s3.S3KtorPresignedRequest
import io.bluetape4k.aws.ktor.s3.s3KtorClientOf
import io.ktor.http.Url
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

object S3KtorExamples {

    fun localStackClient(signingClock: Clock? = null): S3KtorClient =
        s3KtorClientOf(
            region = "ap-northeast-2",
            credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")
            ),
            endpointOverride = Url("http://localhost:4566"),
            addressingStyle = S3KtorAddressingStyle.Path,
            signingClock = signingClock,
        )

    suspend fun uploadAndDownloadText(
        s3: S3KtorClient,
        bucket: String,
        key: String,
        text: String,
    ): String {
        s3.putObject(
            bucket = bucket,
            key = key,
            bytes = text.encodeToByteArray(),
            contentType = "text/plain; charset=utf-8",
            metadata = mapOf("example" to "aws-ktor-s3"),
        )

        return s3.getObjectBytes(bucket, key).decodeToString()
    }

    suspend fun uploadWithDetectedContentType(
        s3: S3KtorClient,
        bucket: String,
        key: String,
        text: String,
    ): String {
        s3.putObjectDetectingContentType(
            bucket = bucket,
            key = key,
            bytes = text.encodeToByteArray(),
            metadata = mapOf("example" to "aws-ktor-s3-detected"),
        )

        return s3.getObjectBytes(bucket, key).decodeToString()
    }

    suspend fun storeAndLoadConfig(
        s3: S3KtorClient,
        bucket: String,
        key: String,
        text: String,
    ): S3KtorConfigObject {
        s3.putConfigObject(
            bucket = bucket,
            key = key,
            text = text,
            metadata = mapOf("example" to "aws-ktor-s3-config"),
        )

        return s3.getConfigObject(bucket, key)
    }

    suspend fun encryptAndDecryptText(
        s3: S3KtorClient,
        bucket: String,
        key: String,
        text: String,
        dataKeyProvider: S3KtorDataKeyProvider = InMemoryS3DataKeyProvider(),
    ): String {
        val encryption = S3KtorClientSideEncryption(dataKeyProvider)
        encryption.putEncryptedObject(
            s3 = s3,
            bucket = bucket,
            key = key,
            plaintext = text.encodeToByteArray(),
            contentType = "text/plain; charset=utf-8",
            metadata = mapOf("example" to "aws-ktor-s3-client-side-encryption"),
        )

        return encryption.getEncryptedObjectBytes(s3, bucket, key).decodeToString()
    }

    fun presignDownload(
        s3: S3KtorClient,
        bucket: String,
        key: String,
        expires: Duration = Duration.ofMinutes(15),
    ): S3KtorPresignedRequest =
        s3.presignGetObject(bucket, key, expires)
}

/**
 * In-memory [S3KtorDataKeyProvider] for local examples and tests.
 *
 * ## Behavior / Contract
 *
 * This provider keeps plaintext data keys only in process memory and returns a
 * deterministic demo key. It is intentionally not a production KMS substitute.
 */
class InMemoryS3DataKeyProvider(
    private val keyId: String = "local-demo-key",
): S3KtorDataKeyProvider {

    private val keys = ConcurrentHashMap<String, ByteArray>()

    override suspend fun generateDataKey(encryptionContext: Map<String, String>): S3KtorDataKey {
        val plaintext = ByteArray(32) { index -> (index + 1).toByte() }
        val encrypted = plaintext.copyOf()
        keys[encrypted.toKey()] = plaintext.copyOf()
        return S3KtorDataKey(
            plaintextKey = plaintext,
            encryptedKey = encrypted,
            keyId = keyId,
        )
    }

    override suspend fun decryptDataKey(
        encryptedDataKey: ByteArray,
        encryptionContext: Map<String, String>,
    ): ByteArray =
        requireNotNull(keys[encryptedDataKey.toKey()]) {
            "Encrypted data key is unknown to this in-memory provider."
        }.copyOf()
}

private fun ByteArray.toKey(): String =
    joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
