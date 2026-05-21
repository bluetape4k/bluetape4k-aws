package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.s3.S3Client
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class S3CoroutinesTemplateAwsEmulatorTest {

    companion object {
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("s3")
        }
        private val bucketName: String = "spring-s3-${UUID.randomUUID()}"
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
            val transferBucketName = "spring-s3-transfer-${UUID.randomUUID()}"
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
}
