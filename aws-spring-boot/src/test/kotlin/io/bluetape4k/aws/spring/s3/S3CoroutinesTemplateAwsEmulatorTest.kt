package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.kms.KmsDataKey
import io.bluetape4k.aws.spring.kms.KmsOperations
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.s3.S3Client
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class S3CoroutinesTemplateAwsEmulatorTest {

    companion object {
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("s3")
        }
        private val bucketName: String = "spring-s3-${Base58.randomString(8).lowercase()}"
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                S3AutoConfiguration::class.java,
                S3TransferAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, { awsEmulator.getCredentialProvider() })
        .withPropertyValues(
            "bluetape4k.aws.s3.region=${awsEmulator.regionName}",
            "bluetape4k.aws.s3.endpoint-override=${awsEmulator.awsEndpoint}",
            "bluetape4k.aws.s3.path-style-access-enabled=true",
            "bluetape4k.aws.s3.presign.duration=PT10M",
        )

    @Test
    fun `upload download list delete object through S3Operations`() {
        contextRunner().run { context ->
            val s3Client = context.getBean(S3Client::class.java)
            val operations = context.getBean(S3Operations::class.java)
            s3Client.createBucket { it.bucket(bucketName) }

            runSuspendIO {
                val key = "docs/readme.txt"
                operations.upload(
                    bucket = bucketName,
                    key = key,
                    contents = "hello s3",
                    contentType = "text/plain",
                )

                operations.existsBucket(bucketName).shouldBeTrue()
                operations.downloadText(bucketName, key) shouldBeEqualTo "hello s3"
                operations.downloadBytes(bucketName, key).toString(StandardCharsets.UTF_8) shouldBeEqualTo "hello s3"

                val page = operations.listPage(bucketName, prefix = "docs/", maxKeys = 10)
                page.objects.map { it.key() } shouldContain key
                operations.listFlow(bucketName, prefix = "docs/", pageSize = 1).toList().map { it.key() } shouldContain key

                val resource = operations.resource(bucketName, key)
                resource.exists().shouldBeTrue()
                resource.filename shouldBeEqualTo "readme.txt"
                resource.contentLength() shouldBeEqualTo "hello s3".toByteArray().size.toLong()
                resource.inputStream.bufferedReader().use { it.readText() } shouldBeEqualTo "hello s3"

                val getUrl = operations.presignGet(bucketName, key).toString()
                getUrl shouldContain bucketName
                getUrl shouldContain key

                val putUrl = operations.presignPut(bucketName, "docs/write.txt", contentType = "text/plain").toString()
                putUrl shouldContain bucketName
                putUrl shouldContain "docs/write.txt"

                operations.delete(bucketName, key)
                operations.listPage(bucketName, prefix = "docs/", maxKeys = 10).objects.shouldBeEmpty()
            }
        }
    }

    @Test
    fun `upload and download files through S3TransferOperations`(
        @TempDir tempDir: Path,
    ) {
        contextRunner().run { context ->
            val s3Client = context.getBean(S3Client::class.java)
            val transferOperations = context.getBean(S3TransferOperations::class.java)
            val transferBucketName = "spring-s3-transfer-${Base58.randomString(8).lowercase()}"
            s3Client.createBucket { it.bucket(transferBucketName) }

            runSuspendIO {
                val key = "large/report.txt"
                val contents = "hello transfer manager"
                val source = tempDir.resolve("report.txt")
                val destination = tempDir.resolve("downloaded-report.txt")
                Files.writeString(source, contents, StandardCharsets.UTF_8)

                val upload = transferOperations.uploadFile(transferBucketName, key, source)
                upload.response().sdkHttpResponse().isSuccessful.shouldBeTrue()

                val inlineKey = "inline/data.bin"
                val inlineUpload = transferOperations.upload(
                    bucket = transferBucketName,
                    key = inlineKey,
                    bytes = contents.toByteArray(StandardCharsets.UTF_8),
                )
                inlineUpload.response().eTag().isNullOrBlank() shouldBeEqualTo false
                transferOperations.downloadBytes(transferBucketName, inlineKey)
                    .result()
                    .asUtf8String() shouldBeEqualTo contents

                val download = transferOperations.downloadFile(transferBucketName, key, destination)
                download.response().sdkHttpResponse().isSuccessful.shouldBeTrue()
                Files.readString(destination, StandardCharsets.UTF_8) shouldBeEqualTo contents

                val bytes = transferOperations.downloadBytes(transferBucketName, key)
                bytes.result().asUtf8String() shouldBeEqualTo contents
            }
        }
    }

    @Test
    fun `upload and download encrypted object through S3 client side encryption`() {
        contextRunner()
            .withBean(KmsOperations::class.java, { FixedS3KmsOperations })
            .withPropertyValues(
                "bluetape4k.aws.s3.client-side-encryption.enabled=true",
                "bluetape4k.aws.s3.client-side-encryption.key-id=alias/test-s3",
                "bluetape4k.aws.s3.client-side-encryption.encryption-context.service=s3-test",
            )
            .run { context ->
                val s3Client = context.getBean(S3Client::class.java)
                val encryptedOperations = context.getBean(S3ClientSideEncryptionOperations::class.java)
                val encryptedBucketName = "spring-s3-enc-${Base58.randomString(8).lowercase()}"
                s3Client.createBucket { it.bucket(encryptedBucketName) }

                runSuspendIO {
                    val key = "encrypted/report.txt"
                    val plaintext = "hello encrypted s3"

                    encryptedOperations.uploadEncrypted(
                        bucket = encryptedBucketName,
                        key = key,
                        bytes = plaintext.toByteArray(StandardCharsets.UTF_8),
                        contentType = "text/plain",
                    )

                    val stored = s3Client.getObjectAsBytes { it.bucket(encryptedBucketName).key(key) }
                    stored.asByteArray().contentEquals(plaintext.toByteArray(StandardCharsets.UTF_8)) shouldBeEqualTo false
                    stored.response().metadata().keys shouldContain "bt4k-cek-alg"

                    encryptedOperations.downloadEncryptedText(encryptedBucketName, key) shouldBeEqualTo plaintext
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
            keyId = keyId ?: "alias/test-s3",
            plaintext = plaintextKey,
            encryptedDataKey = encryptedKey,
        )
}
