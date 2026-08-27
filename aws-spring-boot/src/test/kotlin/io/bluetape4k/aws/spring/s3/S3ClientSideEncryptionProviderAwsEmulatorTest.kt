package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.s3.S3Client
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.deleteIfExists

/** Floci에서 AES/RSA provider의 byte·typed·stream/file 경계를 검증합니다. */
@Execution(ExecutionMode.SAME_THREAD)
class S3ClientSideEncryptionProviderAwsEmulatorTest {

    @Test
    fun `AES provider round trips byte typed and transfer payload`(
        @TempDir
        tempDir: Path,
    ) {
        contextRunner(ClientSideEncryptionProvider.AES).run { context ->
            val encrypted = context.getBean(S3ClientSideEncryptionOperations::class.java)
            val transfer = context.getBean(S3ClientSideEncryptionTransferOperations::class.java)
            val s3Client = context.getBean(S3Client::class.java)
            val bucket = ownerBucket("aes")
            s3Client.createBucket { it.bucket(bucket) }

            try {
                runSuspendIO {
                    val byteKey = "issue-475/$ownerToken/aes.txt"
                    val bytePlaintext = "aes payload"
                    encrypted.uploadEncrypted(
                        bucket = bucket,
                        key = byteKey,
                        bytes = bytePlaintext.toByteArray(StandardCharsets.UTF_8),
                    )
                    encrypted.downloadEncryptedText(bucket, byteKey) shouldBeEqualTo bytePlaintext

                    val stored = s3Client.getObjectAsBytes { it.bucket(bucket).key(byteKey) }
                    stored.asByteArray().contentEquals(bytePlaintext.toByteArray(StandardCharsets.UTF_8))
                        .shouldBeFalse()
                    stored.response().metadata()["bt4k-cek-provider"] shouldBeEqualTo "aes"
                    stored.response().metadata().keys shouldContain "bt4k-cek-key-version"

                    @Suppress("UNCHECKED_CAST")
                    val converter = JacksonS3ObjectConverter(tools.jackson.databind.ObjectMapper()) as
                        S3ObjectConverter<Map<String, Any>>
                    val typedKey = "issue-475/$ownerToken/aes.json"
                    val typed = mapOf("issue" to 475, "provider" to "aes")
                    encrypted.uploadEncryptedObject(bucket, typedKey, typed, converter)
                    @Suppress("UNCHECKED_CAST")
                    val targetType = Map::class.java as Class<Map<String, Any>>
                    encrypted.downloadEncryptedObject(
                        bucket,
                        typedKey,
                        targetType,
                        converter,
                    ) shouldBeEqualTo typed

                    val streamKey = "issue-475/$ownerToken/aes-stream.bin"
                    val streamPlaintext = ByteArray(32 * 1024) { 0x41.toByte() }
                    transfer.encryptedOutputStream(bucket, streamKey).use { output ->
                        output.write(streamPlaintext)
                    }
                    val streamStored = s3Client.getObjectAsBytes { it.bucket(bucket).key(streamKey) }
                    streamStored.asByteArray().contentEquals(streamPlaintext).shouldBeFalse()
                    streamStored.response().metadata()["bt4k-cek-provider"] shouldBeEqualTo "aes"

                    val destination = tempDir.resolve("aes-download.bin")
                    transfer.downloadEncryptedFile(bucket, streamKey, destination)
                    Files.readAllBytes(destination).contentEquals(streamPlaintext).shouldBeTrue()
                    destination.deleteIfExists()
                }
            } finally {
                cleanupBucket(
                    s3Client,
                    bucket,
                    "issue-475/$ownerToken/aes.txt",
                    "issue-475/$ownerToken/aes.json",
                    "issue-475/$ownerToken/aes-stream.bin",
                )
            }
        }
    }

    @Test
    fun `RSA provider stores ciphertext and round trips its own key`() {
        contextRunner(ClientSideEncryptionProvider.RSA).run { context ->
            val encrypted = context.getBean(S3ClientSideEncryptionOperations::class.java)
            val s3Client = context.getBean(S3Client::class.java)
            val bucket = ownerBucket("rsa")
            s3Client.createBucket { it.bucket(bucket) }

            try {
                runSuspendIO {
                    val key = "issue-475/$ownerToken/rsa.bin"
                    val plaintext = "rsa payload"
                    encrypted.uploadEncrypted(bucket, key, plaintext.toByteArray(StandardCharsets.UTF_8))
                    encrypted.downloadEncryptedText(bucket, key) shouldBeEqualTo plaintext

                    val stored = s3Client.getObjectAsBytes { it.bucket(bucket).key(key) }
                    stored.asByteArray().contentEquals(plaintext.toByteArray(StandardCharsets.UTF_8))
                        .shouldBeFalse()
                    stored.response().metadata()["bt4k-cek-provider"] shouldBeEqualTo "rsa"
                    stored.response().metadata()["bt4k-cek-wrap-nonce"] shouldBeEqualTo null
                }
            } finally {
                cleanupBucket(s3Client, bucket, "issue-475/$ownerToken/rsa.bin")
            }
        }
    }

    private fun cleanupBucket(s3Client: S3Client, bucket: String, vararg keys: String) {
        keys.forEach { key ->
            runCatching { s3Client.deleteObject { it.bucket(bucket).key(key) } }
        }
        runCatching { s3Client.deleteBucket { it.bucket(bucket) } }
    }

    private fun contextRunner(provider: ClientSideEncryptionProvider): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    S3AutoConfiguration::class.java,
                    S3CrtAsyncClientAutoConfiguration::class.java,
                    S3TransferAutoConfiguration::class.java,
                ),
            )
            .withBean(AwsCredentialsProvider::class.java, { emulator.getCredentialProvider() })
            .withPropertyValues(
                "bluetape4k.aws.s3.region=${emulator.regionName}",
                "bluetape4k.aws.s3.endpoint-override=${emulator.awsEndpoint}",
                "bluetape4k.aws.s3.path-style-access-enabled=true",
                "bluetape4k.aws.s3.client-side-encryption.enabled=true",
                "bluetape4k.aws.s3.client-side-encryption.provider=${provider.name}",
                "bluetape4k.aws.s3.client-side-encryption.key-id=issue-475-$ownerToken-$provider",
                "bluetape4k.aws.s3.client-side-encryption.key-version=v1",
                "bluetape4k.aws.s3.client-side-encryption.encryption-context.service=issue-475",
            )
            .withBean(provider)

    private fun ownerBucket(provider: String): String =
        "spring-s3-issue-475-$provider-$ownerToken"

    private fun ApplicationContextRunner.withBean(provider: ClientSideEncryptionProvider): ApplicationContextRunner =
        when (provider) {
            ClientSideEncryptionProvider.AES ->
                withBean(S3AesProvider::class.java, {
                    S3AesProvider.of(SecretKeySpec(ByteArray(32) { 0x2A }, "AES"))
                })

            ClientSideEncryptionProvider.RSA ->
                withBean(S3RsaProvider::class.java, {
                    S3RsaProvider.of(
                        KeyPairGenerator.getInstance("RSA")
                            .apply { initialize(2048) }
                            .generateKeyPair(),
                    )
                })

            ClientSideEncryptionProvider.KMS -> error("KMS is not an acceptance provider fixture.")
        }

    companion object {
        private val emulator by lazy { AwsSpringBootTestEmulator.get("s3") }
        private val ownerToken = Base58.randomString(8).lowercase()
    }
}
