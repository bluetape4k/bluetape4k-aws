@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.examples.spring.s3

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.kms.KmsDataKey
import io.bluetape4k.aws.spring.kms.KmsOperations
import io.bluetape4k.aws.spring.s3.S3AutoConfiguration
import io.bluetape4k.aws.spring.s3.S3Operations
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.s3.S3Client
import java.util.UUID

class S3DocumentControllerLocalStackTest {

    companion object {
        private val localStack: LocalStackServer = LocalStackServer().withServices("s3")
        private val bucketName: String = "spring-example-${UUID.randomUUID()}"

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            localStack.start()
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            localStack.stop()
        }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                S3AutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, { localStack.getCredentialProvider() })
        .withBean(KmsOperations::class.java, { FixedS3KmsOperations })
        .withPropertyValues(
            "bluetape4k.aws.s3.region=${localStack.regionName}",
            "bluetape4k.aws.s3.endpoint-override=${localStack.awsEndpoint}",
            "bluetape4k.aws.s3.path-style-access-enabled=true",
            "bluetape4k.aws.s3.presign.duration=PT10M",
            "bluetape4k.aws.s3.client-side-encryption.enabled=true",
            "bluetape4k.aws.s3.client-side-encryption.key-id=alias/example-s3",
        )

    @Test
    fun `controller uploads downloads lists and presigns through LocalStack S3`() {
        contextRunner().run { context ->
            val s3Client = context.getBean(S3Client::class.java)
            s3Client.createBucket { it.bucket(bucketName) }
            val controller = S3DocumentController(
                s3 = context.getBean(S3Operations::class.java),
                encryptedS3Provider = context.getBeanProvider(
                    io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionOperations::class.java
                ),
            )

            runSuspendIO {
                val key = "docs/hello.txt"
                val upload = controller.upload(
                    bucket = bucketName,
                    key = key,
                    bytes = "hello spring s3".encodeToByteArray(),
                    contentType = "text/plain",
                )

                upload.bucket shouldBeEqualTo bucketName
                upload.key shouldBeEqualTo key
                upload.eTag.orEmpty().shouldNotBeEmpty()

                controller.download(bucketName, key).body.contentEquals("hello spring s3".encodeToByteArray()) shouldBeEqualTo true
                controller.listObjects(bucketName, "docs/").toList().map { it.key } shouldContain key
                controller.presignedDownload(bucketName, key).url.toString() shouldContain bucketName
                controller.presignedDownload(bucketName, key).url.toString() shouldContain key
                controller.presignedUpload(bucketName, "docs/write.txt", "text/plain").url.toString() shouldContain bucketName
                controller.presignedUpload(bucketName, "docs/write.txt", "text/plain").url.toString() shouldContain "docs/write.txt"

                val encryptedKey = "docs/secret.txt"
                val plaintext = "encrypted spring s3".encodeToByteArray()
                val encryptedUpload = controller.uploadEncrypted(
                    bucket = bucketName,
                    key = encryptedKey,
                    tenant = "demo",
                    bytes = plaintext,
                    contentType = "text/plain",
                )
                encryptedUpload.eTag.orEmpty().shouldNotBeEmpty()
                val stored = s3Client.getObjectAsBytes { it.bucket(bucketName).key(encryptedKey) }
                stored.asByteArray().contentEquals(plaintext) shouldBeEqualTo false
                stored.response().metadata().keys shouldContain "bt4k-cek-alg"
                controller.downloadEncrypted(bucketName, encryptedKey, tenant = "demo").body.contentEquals(plaintext) shouldBeEqualTo true

                controller.delete(bucketName, key)
                controller.delete(bucketName, encryptedKey)
                controller.listObjects(bucketName, "docs/").toList().shouldBeEmpty()
            }
        }
    }
}

private object FixedS3KmsOperations: KmsOperations {
    private val plaintextKey = ByteArray(32) { (it + 11).toByte() }
    private val encryptedKey = "encrypted-s3-data-key".encodeToByteArray()

    override suspend fun encrypt(
        plaintext: ByteArray,
        keyId: String?,
        encryptionContext: Map<String, String>,
    ): ByteArray =
        plaintext.copyOf()

    override suspend fun decrypt(
        ciphertext: ByteArray,
        keyId: String?,
        encryptionContext: Map<String, String>,
    ): ByteArray =
        plaintextKey.copyOf()

    override suspend fun generateDataKey(
        keyId: String?,
        keySpec: DataKeySpec?,
        numberOfBytes: Int?,
        encryptionContext: Map<String, String>,
        useCache: Boolean,
    ): KmsDataKey =
        KmsDataKey(
            keyId = keyId ?: "alias/example-s3",
            plaintext = plaintextKey,
            encryptedDataKey = encryptedKey,
        )
}
