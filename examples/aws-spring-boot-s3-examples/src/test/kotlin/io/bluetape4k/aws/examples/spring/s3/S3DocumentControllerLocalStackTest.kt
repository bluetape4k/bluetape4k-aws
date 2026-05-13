@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.examples.spring.s3

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.s3.S3AutoConfiguration
import io.bluetape4k.aws.spring.s3.S3Operations
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
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
        .withPropertyValues(
            "bluetape4k.aws.s3.region=${localStack.regionName}",
            "bluetape4k.aws.s3.endpoint-override=${localStack.awsEndpoint}",
            "bluetape4k.aws.s3.path-style-access-enabled=true",
            "bluetape4k.aws.s3.presign.duration=PT10M",
        )

    @Test
    fun `controller uploads downloads lists and presigns through LocalStack S3`() {
        contextRunner().run { context ->
            context.getBean(S3Client::class.java).createBucket { it.bucket(bucketName) }
            val controller = S3DocumentController(context.getBean(S3Operations::class.java))

            runTest {
                val key = "docs/hello.txt"
                val upload = controller.upload(
                    bucket = bucketName,
                    key = key,
                    bytes = "hello spring s3".encodeToByteArray(),
                    contentType = "text/plain",
                )

                assertThat(upload.bucket).isEqualTo(bucketName)
                assertThat(upload.key).isEqualTo(key)
                assertThat(upload.eTag).isNotBlank()

                assertThat(controller.download(bucketName, key).body).isEqualTo("hello spring s3".encodeToByteArray())
                assertThat(controller.listObjects(bucketName, "docs/").toList().map { it.key }).contains(key)
                assertThat(controller.presignedDownload(bucketName, key).url.toString()).contains(bucketName, key)
                assertThat(controller.presignedUpload(bucketName, "docs/write.txt", "text/plain").url.toString())
                    .contains(bucketName, "docs/write.txt")

                controller.delete(bucketName, key)
                assertThat(controller.listObjects(bucketName, "docs/").toList()).isEmpty()
            }
        }
    }
}
