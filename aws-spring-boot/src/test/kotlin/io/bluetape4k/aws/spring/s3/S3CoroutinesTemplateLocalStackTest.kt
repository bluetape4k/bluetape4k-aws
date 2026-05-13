@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.s3.S3Client
import java.nio.charset.StandardCharsets
import java.util.UUID

class S3CoroutinesTemplateLocalStackTest {

    companion object {
        private val localStack: LocalStackServer by lazy {
            LocalStackServer.Launcher.getLocalStack("s3")
        }
        private val bucketName: String = "spring-s3-${UUID.randomUUID()}"
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
}
